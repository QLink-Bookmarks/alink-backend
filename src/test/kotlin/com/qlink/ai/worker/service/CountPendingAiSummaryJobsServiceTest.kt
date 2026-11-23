package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJob
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe

class CountPendingAiSummaryJobsServiceTest :
    BaseServiceTest({
        val service = koinGet<CountPendingAiSummaryJobsService>()
        val aiJobRepository = koinGet<AiJobRepository>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()

        Given("시작 여부가 다른 작업들") {
            var originalCount = 0L

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                originalCount = aiJobRepository.countNotStarted()
                val user = userRepository.insert(UserFixture.createRandomValidUser())
                val (key, model) =
                    insertAiContext(
                        userId = user.id!!,
                        aiProviderRepository = koinGet<AiProviderRepository>(),
                        availableModelRepository = koinGet<AvailableModelRepository>(),
                        userProviderRepository = koinGet<UserProviderRepository>(),
                    )
                val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id))
                listOf(null, RandomFixture.randomPastInstant()).forEach { startedAt ->
                    aiJobRepository.insert(
                        AiJob(
                            linkId = link.id!!,
                            userProviderId = key.id!!,
                            requestModelId = model.id!!,
                            requestedUrl = link.url,
                            prompt = RandomFixture.randomSentenceWithMax(1000),
                            startedAt = startedAt,
                        ),
                    )
                }
            }

            When("대기량을 조회하면") {
                Then("미시작 작업만 집계한다") {
                    val count = service.count()

                    count shouldBe originalCount + 1
                    count shouldBe aiJobRepository.countNotStarted()
                }
            }
        }
    })
