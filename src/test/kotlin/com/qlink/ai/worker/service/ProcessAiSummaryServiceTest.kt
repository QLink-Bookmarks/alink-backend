package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.dto.AiSummaryExecutionResult
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.service.UpdateLinkAiSummaryService
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneOffset

class ProcessAiSummaryServiceTest :
    BaseServiceTest({
        val service = koinGet<ProcessAiSummaryService>()
        val requestService = koinGet<UpdateLinkAiSummaryService>()
        val commandChannel = koinGet<Channel<Long>>()
        val aiJobRepository = koinGet<AiJobRepository>()
        val aiProviderRepository = koinGet<AiProviderRepository>()
        val modelRepository = koinGet<AvailableModelRepository>()
        val userProviderRepository = koinGet<UserProviderRepository>()
        val dailyUsageRepository = koinGet<DailyUsageRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val userRepository = koinGet<UserRepository>()
        val aiClient = koinGet<FakeAiClient>()

        Given("요약 작업에서 요청한 모델의 당일 사용량") {
            var jobId = 0L

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                aiClient.reset()
                val userId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = userId))
                val (userProvider, model) =
                    insertAiContext(
                        userId = userId,
                        aiProviderRepository = aiProviderRepository,
                        availableModelRepository = modelRepository,
                        userProviderRepository = userProviderRepository,
                    )
                requestService.updateLinkAiSummary(
                    loginId = userId,
                    request = AiSummaryRequest(id = link.id!!, userProviderId = userProvider.id!!, modelId = model.id!!, url = link.url),
                )
                jobId = withTimeout(1_000) { commandChannel.receive() }
                dailyUsageRepository.insert(
                    DailyUsage(
                        userProviderId = userProvider.id,
                        modelId = model.id,
                        usageDate = LocalDate.now(ZoneOffset.UTC),
                        requests = model.rpdLimit!!,
                    ),
                )
            }

            When("당일 요청 한도에 도달한 모델의 작업을 선점하면") {
                Then("외부 AI 호출 없이 실패 결과를 반환하고 선점 정보가 저장된다") {
                    val outcome = service.proceed(jobId).shouldBeInstanceOf<AiSummaryExecutionResult.Failed>()

                    outcome.jobId shouldBe jobId
                    aiClient.requestedModels shouldBe emptyList()
                    val saved = aiJobRepository.findById(jobId)!!
                    saved.status shouldBe AiJobStatus.P
                    saved.startedAt shouldNotBe null
                }
            }
        }
    })
