package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.service.UpdateLinkAiSummaryService
import com.qlink.ai.worker.AiSummaryWorker
import com.qlink.auth.domain.Role
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.copyForTestActive
import com.qlink.support.fixture.createAiSummaryWorker
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

class AiSummaryWorkerRegressionTest :
    BaseServiceTest({
        val service = koinGet<UpdateLinkAiSummaryService>()
        val worker = koinGet<AiSummaryWorker>()
        val commandChannel = koinGet<Channel<Long>>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val aiProviderRepository = koinGet<AiProviderRepository>()
        val availableModelRepository = koinGet<AvailableModelRepository>()
        val userProviderRepository = koinGet<UserProviderRepository>()
        val aiJobRepository = koinGet<AiJobRepository>()
        val dailyUsageRepository = koinGet<DailyUsageRepository>()
        val fakeAiClient = koinGet<FakeAiClient>()

        suspend fun insertAiContext(
            userId: Long,
            role: Role = Role.NORMAL,
        ) = insertAiContext(
            userId = userId,
            aiProviderRepository = aiProviderRepository,
            availableModelRepository = availableModelRepository,
            userProviderRepository = userProviderRepository,
            role = role,
        )

        Given("선점 가능한 요약 작업") {
            lateinit var link: Link
            lateinit var key: UserProvider
            lateinit var model: AvailableModel
            var jobId = 0L

            suspend fun enqueue(role: Role = Role.NORMAL) {
                val user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                val context = insertAiContext(userId = user.id, role = role)
                key = context.first
                model = context.second
                service.updateLinkAiSummary(
                    loginId = user.id,
                    request = AiSummaryRequest(id = link.id!!, userProviderId = key.id!!, modelId = model.id!!, url = link.url),
                )
                jobId = withTimeout(1_000) { commandChannel.receive() }
            }

            beforeTest {
                fakeAiClient.reset()
            }

            When("외부 요약을 기다리는 동안 같은 작업의 실행 요청이 오면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue()
                }

                Then("외부 호출은 한 번만 수행되고 선점한 실행의 결과가 저장된다") {
                    coroutineScope {
                        val entered = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        fakeAiClient.beforeSummary = {
                            entered.complete(Unit)
                            release.await()
                        }
                        val first = async { worker.proceed(jobId) }
                        try {
                            withTimeout(5_000) { entered.await() }
                            worker.proceed(jobId)
                        } finally {
                            release.complete(Unit)
                        }
                        first.await()
                    }

                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.C
                    linkRepository.findById(link.id!!)!!.summary shouldBe "AI 요약"
                }
            }

            When("선점한 작업의 저장된 키를 복호화할 수 없으면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue()
                    userProviderRepository.update(
                        UserProvider(
                            id = key.id,
                            userId = key.userId,
                            providerId = key.providerId,
                            userRole = key.userRole,
                            apiKey = RandomFixture.randomFixedSentence(17),
                        ),
                    )
                }

                val action = suspend { worker.proceed(jobId) }

                Then("시작 기록을 남기고 외부 호출과 완료·실패를 생략한다") {
                    shouldNotThrow<BusinessException> { action() }

                    val saved = aiJobRepository.findById(jobId)!!
                    (saved.startedAt != null) shouldBe true
                    saved.completedAt shouldBe null
                    saved.status shouldBe AiJobStatus.P
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.G
                    fakeAiClient.requestedModels shouldBe emptyList()
                }
            }

            When("일반 사용자의 요청 모델이 실패하면") {
                lateinit var reason: String

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue()
                    availableModelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2))
                    reason = RandomFixture.randomSentenceWithMax(100)
                    fakeAiClient.failuresByModel = mapOf(model.model to IllegalStateException(reason))
                }

                val action = suspend { worker.proceed(jobId) }

                Then("요청 모델을 한 번만 사용하고 실패를 저장한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.summary shouldBe reason
                }
            }

            listOf(
                ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE to { detail: String -> IOException(detail) },
                ErrorCode.AI_EMPTY_RESPONSE to { detail: String -> JsonConvertException(detail) },
            ).forEach { (error, failure) ->
                When("레거시 공급자가 ${error.name}에 해당하는 전송 또는 응답 오류를 반환하면") {
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue()
                        fakeAiClient.failuresByModel = mapOf(model.model to failure(RandomFixture.randomSentenceWithMax(100)))
                    }

                    val action = suspend { worker.proceed(jobId) }

                    Then("일반 사용자의 모델을 한 번 호출하고 정제된 실패를 저장한다") {
                        shouldNotThrow<BusinessException> { action() }
                        fakeAiClient.requestedModels shouldBe listOf(model.model)
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.summary shouldBe error.message
                    }
                }
            }

            When("SUPER_ADMIN의 첫 후보가 실패하고 다음 후보가 성공하면") {
                lateinit var fallback: AvailableModel

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                    fallback =
                        availableModelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2))
                    fakeAiClient.failuresByModel = mapOf(model.model to IllegalStateException(RandomFixture.randomSentenceWithMax(100)))
                }

                val action = suspend { worker.proceed(jobId) }

                Then("후보 순서대로 호출하고 성공 모델로 완료한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe listOf(model.model, fallback.model)
                    val saved = aiJobRepository.findById(jobId)!!
                    saved.status shouldBe AiJobStatus.C
                    saved.responseModelId shouldBe fallback.id
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.A
                }
            }

            When("당일 요청 한도에 도달하면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue()
                    dailyUsageRepository.insert(
                        DailyUsage(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = LocalDate.now(ZoneOffset.UTC),
                            requests = model.rpdLimit!!,
                        ),
                    )
                }

                val action = suspend { worker.proceed(jobId) }

                Then("외부 호출 없이 작업과 링크에 실패를 저장한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe emptyList()
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                }
            }

            When("슈퍼 관리자의 두 모델이 계속 실패하면") {
                lateinit var fallback: AvailableModel
                lateinit var reason: String

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                    fallback =
                        availableModelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2))
                    reason = RandomFixture.randomSentenceWithMax(100)
                    fakeAiClient.failuresByModel =
                        mapOf(
                            model.model to IllegalStateException(RandomFixture.randomSentenceWithMax(100)),
                            fallback.model to IllegalStateException(reason),
                        )
                }

                val action = suspend { worker.proceed(jobId) }

                Then("후보를 다섯 번 순회하고 마지막 실패를 저장한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe List(5) { listOf(model.model, fallback.model) }.flatten()
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.summary shouldBe reason
                }
            }

            When("첫 호출이 실패하는 동안 공급자가 비활성화되면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                    availableModelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2))
                    val provider = aiProviderRepository.findById(key.providerId)!!
                    fakeAiClient.failuresByModel =
                        mapOf(model.model to IllegalStateException(RandomFixture.randomSentenceWithMax(100)))
                    fakeAiClient.beforeSummary = {
                        aiProviderRepository.update(provider.copyForTestActive(isActive = false))
                    }
                }

                val action = suspend { worker.proceed(jobId) }

                Then("두 번째 외부 호출 없이 작업과 링크가 실패한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                }
            }

            When("AI 응답이 같은 소유자의 다른 링크를 가리키면") {
                lateinit var otherLink: Link

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue()
                    otherLink = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = link.ownerId))
                    fakeAiClient.linkId = otherLink.id
                }

                val action = suspend { worker.proceed(jobId) }

                Then("작업에 링크 불일치를 남기고 응답이 가리킨 링크는 보존한다") {
                    shouldNotThrow<BusinessException> { action() }

                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.summary shouldBe ErrorCode.AI_SUMMARY_LINK_MISMATCH.message
                    val saved = linkRepository.findById(otherLink.id!!)!!
                    saved.status shouldBe otherLink.status
                    saved.summary shouldBe otherLink.summary
                    saved.title shouldBe otherLink.title
                    saved.updatedAt shouldBe otherLink.updatedAt
                }
            }

            fun workerWithRepositories(
                availableModels: AvailableModelRepository = availableModelRepository,
                aiProviders: AiProviderRepository = aiProviderRepository,
                afterUsageLoaded: () -> Unit = {},
            ): AiSummaryWorker =
                createAiSummaryWorker(
                    process =
                        ProcessAiSummaryService(
                            tx = koinGet(),
                            aiJobRepository = aiJobRepository,
                            userProviderRepository = userProviderRepository,
                            availableModelRepository = availableModels,
                            aiProviderRepository = aiProviders,
                            dailyUsageRepository =
                                object : DailyUsageRepository by dailyUsageRepository {
                                    override suspend fun findAllByUserProviderAndUsageDate(
                                        userProviderId: Long,
                                        modelIds: List<Long>,
                                        usageDate: LocalDate,
                                    ): List<DailyUsage> =
                                        dailyUsageRepository
                                            .findAllByUserProviderAndUsageDate(
                                                userProviderId = userProviderId,
                                                modelIds = modelIds,
                                                usageDate = usageDate,
                                            ).also { afterUsageLoaded() }
                                },
                            folderRepository = koinGet(),
                            linkRepository = linkRepository,
                            aiClientRouter = koinGet(),
                            apiKeyCipher = koinGet(),
                            log = LoggerFactory.getLogger(ProcessAiSummaryService::class.java),
                        ),
                )

            fun workerWithMissingContextRow(
                errorCode: ErrorCode,
                afterUsageLoaded: () -> Unit = {},
                isMissing: () -> Boolean,
            ): AiSummaryWorker =
                workerWithRepositories(
                    availableModels =
                        object : AvailableModelRepository by availableModelRepository {
                            override suspend fun findById(modelId: Long): AvailableModel? =
                                if (isMissing() && errorCode == ErrorCode.AI_MODEL_NOT_FOUND) {
                                    null
                                } else {
                                    availableModelRepository.findById(modelId)
                                }
                        },
                    aiProviders =
                        object : AiProviderRepository by aiProviderRepository {
                            override suspend fun findById(providerId: Long): AiProvider? =
                                if (isMissing() && errorCode == ErrorCode.AI_PROVIDER_NOT_FOUND) {
                                    null
                                } else {
                                    aiProviderRepository.findById(providerId)
                                }
                        },
                    afterUsageLoaded = afterUsageLoaded,
                )

            listOf(ErrorCode.AI_PROVIDER_NOT_FOUND, ErrorCode.AI_MODEL_NOT_FOUND).forEach { errorCode ->
                When("최초 조회에서 ${errorCode.name} 상황이 발생하면") {
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue()
                    }

                    val action = suspend { workerWithMissingContextRow(errorCode) { true }.proceed(jobId) }

                    Then("선점 기록만 남고 외부 요약은 호출하지 않는다") {
                        shouldNotThrow<BusinessException> { action() }

                        fakeAiClient.requestedModels shouldBe emptyList()
                        val saved = aiJobRepository.findById(jobId)!!
                        (saved.startedAt != null) shouldBe true
                        saved.completedAt shouldBe null
                        saved.status shouldBe AiJobStatus.P
                        linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.G
                    }
                }

                When("시도 직전 ${errorCode.name} 상황이 발생하면") {
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue()
                    }

                    val action =
                        suspend {
                            var usageLoaded = false
                            workerWithMissingContextRow(errorCode = errorCode, afterUsageLoaded = { usageLoaded = true }) {
                                usageLoaded
                            }.proceed(jobId)
                        }

                    Then("외부 호출 없이 해당 실패 사유를 저장한다") {
                        shouldNotThrow<BusinessException> { action() }

                        fakeAiClient.requestedModels shouldBe emptyList()
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.summary shouldBe errorCode.message
                    }
                }

                When("첫 호출 실패 후 재시도 직전 ${errorCode.name} 상황이 발생하면") {
                    var firstCallStarted = false

                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue(Role.SUPER_ADMIN)
                        firstCallStarted = false
                        fakeAiClient.failuresByModel =
                            mapOf(model.model to IllegalStateException(RandomFixture.randomSentenceWithMax(100)))
                        fakeAiClient.beforeSummary = { firstCallStarted = true }
                    }

                    val action = suspend { workerWithMissingContextRow(errorCode) { firstCallStarted }.proceed(jobId) }

                    Then("추가 외부 호출 없이 해당 실패 사유를 저장한다") {
                        shouldNotThrow<BusinessException> { action() }

                        fakeAiClient.requestedModels shouldBe listOf(model.model)
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.summary shouldBe errorCode.message
                    }
                }
            }

            When("사용량 확인 뒤 첫 후보가 비활성화되면") {
                lateinit var fallback: AvailableModel

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                    fallback =
                        availableModelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2))
                }

                val action =
                    suspend {
                        var usageLoaded = false
                        workerWithRepositories(
                            availableModels =
                                object : AvailableModelRepository by availableModelRepository {
                                    override suspend fun findById(modelId: Long): AvailableModel? {
                                        val found = availableModelRepository.findById(modelId)
                                        return if (usageLoaded && modelId == model.id) {
                                            found?.copyForTestActive(isActive = false)
                                        } else {
                                            found
                                        }
                                    }
                                },
                            afterUsageLoaded = { usageLoaded = true },
                        ).proceed(jobId)
                    }

                Then("비활성 후보를 건너뛰고 다음 모델로 완료한다") {
                    shouldNotThrow<BusinessException> { action() }

                    fakeAiClient.requestedModels shouldBe listOf(fallback.model)
                    val saved = aiJobRepository.findById(jobId)!!
                    saved.status shouldBe AiJobStatus.C
                    saved.responseModelId shouldBe fallback.id
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.A
                }
            }

            When("외부 요약을 기다리는 코루틴을 취소하면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                }

                Then("취소가 전파되고 재시도나 완료·실패 기록 없이 선점만 남는다") {
                    coroutineScope {
                        val entered = CompletableDeferred<Unit>()
                        fakeAiClient.beforeSummary = {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                        val execution = async { worker.proceed(jobId) }
                        try {
                            withTimeout(5_000) { entered.await() }
                        } finally {
                            execution.cancelAndJoin()
                        }
                        shouldThrow<CancellationException> { execution.await() }
                    }

                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    val saved = aiJobRepository.findById(jobId)!!
                    (saved.startedAt != null) shouldBe true
                    saved.completedAt shouldBe null
                    saved.status shouldBe AiJobStatus.P
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.G
                }
            }
        }
    })
