package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.repository.table.AiProviders
import com.qlink.ai.repository.table.AvailableModels
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RecoverAiSummaryJobsServiceTest :
    BaseServiceTest({
        val service = koinGet<RecoverAiSummaryJobsService>()
        val tx = koinGet<TransactionRunner>()
        val aiJobRepository = koinGet<AiJobRepository>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()

        suspend fun insertJobs(times: List<Pair<Instant, Instant?>>): Pair<Long, List<AiJob>> {
            val userId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
            val (key, model) =
                insertAiContext(
                    userId = userId,
                    aiProviderRepository = koinGet<AiProviderRepository>(),
                    availableModelRepository = koinGet<AvailableModelRepository>(),
                    userProviderRepository = koinGet<UserProviderRepository>(),
                )
            val jobs =
                times.map { (requestedAt, startedAt) ->
                    val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = userId))
                    aiJobRepository.insert(
                        AiFixture.createPendingAiJobOf(
                            link = link,
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            requestedAt = requestedAt,
                            startedAt = startedAt,
                        ),
                    )
                }
            return userId to jobs
        }

        Given("DB 시각을 기준으로 복구 대기 중인 작업") {
            When("만료·재실행·대기 유지 대상이 섞이면") {
                lateinit var jobs: List<AiJob>

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val now = aiJobRepository.now()
                    val stale = now - AiJob.STALE_AFTER - 1.minutes
                    val recent = now - 1.minutes
                    jobs = insertJobs(listOf(stale to null, recent to null, recent to recent, stale to stale)).second
                }

                Then("만료 작업만 실패로 저장하고 재실행 대상 ID만 반환한다") {
                    val (expired, runnable, inProgress, expiredStarted) = jobs
                    val recorded = mutableListOf<Long>()
                    val runnableIds = service.recover { recorded.add(it) }

                    runnableIds shouldBe listOf(runnable.id)
                    recorded shouldBe listOf(expired.id, expiredStarted.id)
                    jobs.map { aiJobRepository.findById(it.id!!)!!.status } shouldBe
                        listOf(AiJobStatus.F, AiJobStatus.P, AiJobStatus.P, AiJobStatus.F)
                    linkRepository.findById(expired.linkId)!!.status shouldBe LinkStatus.F
                    linkRepository.findById(expiredStarted.linkId)!!.status shouldBe LinkStatus.F
                    aiJobRepository.findById(inProgress.id!!)!!.startedAt shouldBe inProgress.startedAt
                }
            }

            When("뒤쪽 만료 작업의 실패 저장이 중단되면") {
                var userId = 0L
                var providerId = 0L
                lateinit var jobs: List<AiJob>
                val testModule =
                    module {
                        factory {
                            val interruptingRepository =
                                object : AiJobRepository by aiJobRepository {
                                    override suspend fun update(aiJob: AiJob): AiJob {
                                        if (aiJob.id == jobs.last().id) throw BusinessException(ErrorCode.LINK_NOT_FOUND)
                                        return aiJobRepository.update(aiJob)
                                    }
                                }
                            RecoverAiSummaryJobsService(
                                tx = get(),
                                aiJobRepository = get(),
                                failAiSummaryService =
                                    FailAiSummaryService(
                                        tx = get(),
                                        aiJobRepository = interruptingRepository,
                                        linkRepository = get(),
                                        urlResolver = get(),
                                    ),
                            )
                        }
                    }

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            tx.required {
                                val stale = aiJobRepository.now() - AiJob.STALE_AFTER - 1.minutes
                                val inserted = insertJobs(List(3) { stale to null })
                                userId = inserted.first
                                jobs = inserted.second
                                providerId = koinGet<UserProviderRepository>().findById(jobs.first().userProviderId)!!.providerId
                            }
                        }.await()
                    loadKoinModules(testModule)
                }

                afterTest { (testCase, _) ->
                    if (testCase.type != TestType.Test) return@afterTest
                    unloadKoinModules(testModule)
                    GlobalContext.get().declare(service)
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            tx.required {
                                linkRepository.deleteAllByIds(jobs.map { it.linkId })
                                userRepository.deleteById(userId)
                                AvailableModels.deleteWhere { id eq jobs.first().requestModelId }
                                AiProviders.deleteWhere { id eq providerId }
                            }
                        }.await()
                }

                Then("별도 트랜잭션에서 앞선 작업의 실패를 조회할 수 있고 콜백은 작업마다 한 번 실행된다") {
                    val recorded = mutableListOf<Long>()
                    val recoveringService = koinGet<RecoverAiSummaryJobsService>()
                    val action =
                        suspend {
                            CoroutineScope(Dispatchers.IO)
                                .async {
                                    recoveringService.recover { expiredId ->
                                        tx.readOnly { aiJobRepository.findById(expiredId)!!.status } shouldBe AiJobStatus.F
                                        recorded.add(expiredId)
                                    }
                                }.await()
                        }

                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_NOT_FOUND.message) { action() }

                    recorded shouldBe jobs.dropLast(1).map { it.id }
                    val statuses =
                        CoroutineScope(Dispatchers.IO)
                            .async {
                                tx.readOnly { jobs.map { aiJobRepository.findById(it.id!!)!!.status } }
                            }.await()
                    statuses shouldBe listOf(AiJobStatus.F, AiJobStatus.F, AiJobStatus.P)
                }
            }
        }
    })
