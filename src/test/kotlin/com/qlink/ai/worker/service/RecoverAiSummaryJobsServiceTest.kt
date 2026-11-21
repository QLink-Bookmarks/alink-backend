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
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.createAiSummaryWorker
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RecoverAiSummaryJobsServiceTest :
    BaseServiceTest({
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
                        AiJob(
                            linkId = link.id!!,
                            userProviderId = key.id!!,
                            requestModelId = model.id!!,
                            requestedUrl = link.url,
                            prompt = RandomFixture.randomSentenceWithMax(1000),
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
                    val channel = Channel<Long>(capacity = Channel.UNLIMITED)
                    val registry = SimpleMeterRegistry()
                    createAiSummaryWorker(meterRegistry = registry, channel = channel).recover()

                    channel.tryReceive().getOrNull() shouldBe runnable.id
                    channel.tryReceive().isFailure shouldBe true
                    registry
                        .get("ai_summary_results")
                        .tag("result", "expired")
                        .counter()
                        .count() shouldBe 2.0
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
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            tx.required {
                                val stale = aiJobRepository.now() - AiJob.STALE_AFTER - 1.minutes
                                val inserted = insertJobs(List(3) { stale to null } + (aiJobRepository.now() to null))
                                userId = inserted.first
                                jobs = inserted.second
                                providerId = koinGet<UserProviderRepository>().findById(jobs.first().userProviderId)!!.providerId
                            }
                        }.await()
                }

                afterTest { (testCase, _) ->
                    if (testCase.type != TestType.Test) return@afterTest
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

                Then("앞선 두 건의 커밋과 계측을 유지하고 재실행 신호를 보내지 않는다") {
                    val registry = SimpleMeterRegistry()
                    val channel = Channel<Long>(capacity = Channel.UNLIMITED)
                    val expiredJobs = jobs.take(3)
                    val failingRepository =
                        object : AiJobRepository by aiJobRepository {
                            override suspend fun update(aiJob: AiJob): AiJob {
                                if (aiJob.id == expiredJobs.last().id) {
                                    expiredJobs.dropLast(1).map { aiJobRepository.findById(it.id!!)!!.status } shouldBe
                                        listOf(AiJobStatus.F, AiJobStatus.F)
                                    registry
                                        .get("ai_summary_results")
                                        .tag("result", "expired")
                                        .counter()
                                        .count() shouldBe 2.0
                                    throw BusinessException(ErrorCode.LINK_NOT_FOUND)
                                }
                                return aiJobRepository.update(aiJob)
                            }
                        }
                    val fail =
                        FailAiSummaryService(
                            tx = tx,
                            aiJobRepository = failingRepository,
                            linkRepository = linkRepository,
                            urlResolver = koinGet(),
                        )
                    val worker = createAiSummaryWorker(fail = fail, meterRegistry = registry, channel = channel)
                    val recover =
                        suspend {
                            CoroutineScope(Dispatchers.IO).async { worker.recover() }.await()
                        }

                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_NOT_FOUND.message) { recover() }

                    val statuses =
                        CoroutineScope(Dispatchers.IO)
                            .async {
                                tx.readOnly { jobs.map { aiJobRepository.findById(it.id!!)!!.status } }
                            }.await()
                    statuses shouldBe listOf(AiJobStatus.F, AiJobStatus.F, AiJobStatus.P, AiJobStatus.P)
                    registry
                        .get("ai_summary_results")
                        .tag("result", "expired")
                        .counter()
                        .count() shouldBe 2.0
                    channel.tryReceive().isFailure shouldBe true
                }
            }
        }
    })
