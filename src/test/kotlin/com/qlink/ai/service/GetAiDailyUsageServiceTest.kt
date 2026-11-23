package com.qlink.ai.service

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiDailyUsageItemResponse
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class GetAiDailyUsageServiceTest :
    BaseServiceTest({
        val service = koinGet<GetAiDailyUsageService>()
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val usages = koinGet<DailyUsageRepository>()

        Given("운영 계정 공용 키와 다른 계정의 키 사용량이 있는 제공자") {
            lateinit var admin: User
            lateinit var provider: AiProvider
            lateinit var model: AvailableModel
            lateinit var date: LocalDate
            lateinit var shared: UserProvider
            lateinit var sharedUsage: DailyUsage

            beforeTest {
                if (it.type != TestType.Test) {
                    return@beforeTest
                }
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                provider = providers.insert(AiFixture.createRandomValidAiProvider(isActive = false))
                val providerId = provider.id!!
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = providerId, isActive = false))
                val modelId = model.id!!
                date = RandomFixture.randomDateTime().toLocalDate()
                val personalOwner = users.insert(UserFixture.createRandomValidUser())
                val otherAdmin = users.insert(UserFixture.createRandomValidSuperAdmin())
                AiFixture.insertSharedKeyOwnerIfAbsent()
                val personal = keys.insert(AiFixture.createUserProviderOf(userId = personalOwner.id!!, providerId = providerId))
                val otherDefault =
                    keys.insert(
                        UserProvider(
                            userId = otherAdmin.id!!,
                            providerId = providerId,
                            userRole = Role.SUPER_ADMIN,
                            apiKey = RandomFixture.randomFixedSentence(40),
                        ),
                    )
                val requestCount = RandomFixture.randomInt(1, 100)

                fun newUsage(
                    keyId: Long,
                    day: LocalDate,
                    tokens: Int,
                ) = DailyUsage(userProviderId = keyId, modelId = modelId, usageDate = day, requests = requestCount, tokens = tokens)

                shared =
                    keys.insert(
                        UserProvider(
                            userId = UserProvider.SHARED_OWNER_ID,
                            providerId = providerId,
                            userRole = Role.SUPER_ADMIN,
                            apiKey = RandomFixture.randomFixedSentence(40),
                        ),
                    )
                val sharedId = shared.id!!
                usages.insert(newUsage(sharedId, date.plusDays(1), RandomFixture.randomInt(1, 100)))
                sharedUsage = usages.insert(newUsage(sharedId, date, Int.MAX_VALUE - RandomFixture.randomInt(1, 100)))
                usages.insert(newUsage(personal.id!!, date, RandomFixture.randomInt(1, 100)))
                usages.insert(newUsage(otherDefault.id!!, date, RandomFixture.randomInt(1, 100)))
            }

            When("사용량이 있는 UTC 날짜를 조회하면") {
                Then("해당 날짜의 공용 행만 모델별로 합산한다") {
                    val result = service.getAiDailyUsage(loginId = admin.id!!, date = date.toString())
                    result.date shouldBe date.toString()
                    result.items shouldBe
                        listOf(
                            AiDailyUsageItemResponse(
                                providerId = provider.id!!,
                                providerType = provider.type,
                                modelId = model.id!!,
                                model = model.model,
                                requests = sharedUsage.requests.toLong(),
                                tokens = sharedUsage.tokens.toLong(),
                            ),
                        )
                }
            }

            When("윤일과 다음 날의 UTC 자정 경계에 사용량이 있으면") {
                val leapDay = LocalDate.of(2028, 2, 29)
                val nextDay = leapDay.plusDays(1)
                lateinit var leapUsage: DailyUsage
                lateinit var nextUsage: DailyUsage

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    val keyId = shared.id!!
                    val modelId = model.id!!
                    leapUsage =
                        usages.insert(
                            DailyUsage(
                                userProviderId = keyId,
                                modelId = modelId,
                                usageDate = leapDay,
                                requests = RandomFixture.randomInt(1, 100),
                                tokens = RandomFixture.randomInt(1, 1_000),
                            ),
                        )
                    nextUsage =
                        usages.insert(
                            DailyUsage(
                                userProviderId = keyId,
                                modelId = modelId,
                                usageDate = nextDay,
                                requests = leapUsage.requests + 1,
                                tokens = leapUsage.tokens + 1,
                            ),
                        )
                }

                Then("두 UTC 날짜의 기록을 각각 집계한다") {
                    for (expected in listOf(leapUsage, nextUsage)) {
                        val date = expected.usageDate.toString()
                        val result = service.getAiDailyUsage(loginId = admin.id!!, date = date)
                        result.date shouldBe date
                        val item = result.items.single()
                        item.requests shouldBe expected.requests.toLong()
                        item.tokens shouldBe expected.tokens.toLong()
                    }
                }
            }

            When("사용량이 없는 날짜를 조회하면") {
                Then("날짜와 빈 목록을 반환한다") {
                    val emptyDate = date.minusDays(1).toString()
                    val result = service.getAiDailyUsage(loginId = admin.id!!, date = emptyDate)
                    result.date shouldBe emptyDate
                    result.items shouldBe emptyList()
                }
            }

            When("날짜가 비어 있거나 존재하지 않는 날짜이면") {
                Then("잘못된 요청으로 처리한다") {
                    listOf(null, "2026-2-3", "2026-02-30", "2026-11-22T00:00:00Z").forEach { invalidDate ->
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_BAD_REQUEST.message) {
                            service.getAiDailyUsage(loginId = admin.id!!, date = invalidDate)
                        }
                    }
                }
            }
        }
    })
