package com.qlink.ai.worker.service

import com.qlink.ai.client.AiClientRouter
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
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.copyForTestActive
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset

class ProcessAiSummaryServiceTest :
    BaseServiceTest({
        val service = koinGet<UpdateLinkAiSummaryService>()
        val processAiSummaryService = koinGet<ProcessAiSummaryService>()
        val commandChannel = koinGet<Channel<Long>>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val todoRepository = koinGet<TodoRepository>()
        val aiProviderRepository = koinGet<AiProviderRepository>()
        val availableModelRepository = koinGet<AvailableModelRepository>()
        val userProviderRepository = koinGet<UserProviderRepository>()
        val aiJobRepository = koinGet<AiJobRepository>()
        val dailyUsageRepository = koinGet<DailyUsageRepository>()
        val folderRepository = koinGet<FolderRepository>()
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
                        val first = async { processAiSummaryService.proceed(jobId) }
                        try {
                            withTimeout(5_000) { entered.await() }
                            processAiSummaryService.proceed(jobId)
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

                val action = suspend { processAiSummaryService.proceed(jobId) }

                Then("시작 기록을 남기고 외부 호출과 완료·실패를 생략한다") {
                    shouldNotThrowAny { action() }

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

                val action = suspend { processAiSummaryService.proceed(jobId) }

                Then("요청 모델을 한 번만 사용하고 실패를 저장한다") {
                    shouldNotThrowAny { action() }

                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.summary shouldBe reason
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

                val action = suspend { processAiSummaryService.proceed(jobId) }

                Then("후보 순서대로 호출하고 성공 모델로 완료한다") {
                    shouldNotThrowAny { action() }

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

                val action = suspend { processAiSummaryService.proceed(jobId) }

                Then("외부 호출 없이 작업과 링크에 실패를 저장한다") {
                    shouldNotThrowAny { action() }

                    fakeAiClient.requestedModels shouldBe emptyList()
                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                }
            }

            listOf(ErrorCode.AI_PROVIDER_NOT_FOUND, ErrorCode.AI_MODEL_NOT_FOUND).forEach { errorCode ->
                When("시도 직전 ${errorCode.name}이 발생하면") {
                    val originalService = processAiSummaryService
                    val testModule =
                        module {
                            factory {
                                var providerReads = 0
                                var modelReads = 0
                                ProcessAiSummaryService(
                                    tx = get<TransactionRunner>(),
                                    aiJobRepository = get(),
                                    userProviderRepository = get(),
                                    availableModelRepository =
                                        object : AvailableModelRepository by availableModelRepository {
                                            override suspend fun findById(modelId: Long): AvailableModel? =
                                                if (++modelReads == 3 && errorCode == ErrorCode.AI_MODEL_NOT_FOUND) {
                                                    null
                                                } else {
                                                    availableModelRepository.findById(modelId)
                                                }
                                        },
                                    aiProviderRepository =
                                        object : AiProviderRepository by aiProviderRepository {
                                            override suspend fun findById(providerId: Long): AiProvider? =
                                                if (++providerReads == 3 && errorCode == ErrorCode.AI_PROVIDER_NOT_FOUND) {
                                                    null
                                                } else {
                                                    aiProviderRepository.findById(providerId)
                                                }
                                        },
                                    dailyUsageRepository = get(),
                                    folderRepository = get(),
                                    linkRepository = get(),
                                    completeAiSummaryService = get(),
                                    failAiSummaryService = get(),
                                    aiClientRouter = get<AiClientRouter>(),
                                    apiKeyCipher = get<ApiKeyCipher>(),
                                    log = LoggerFactory.getLogger(ProcessAiSummaryService::class.java),
                                )
                            }
                        }

                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue()
                    }

                    val action =
                        suspend {
                            loadKoinModules(testModule)
                            try {
                                koinGet<ProcessAiSummaryService>().proceed(jobId)
                            } finally {
                                unloadKoinModules(testModule)
                                GlobalContext.get().declare(originalService)
                            }
                        }

                    Then("외부 호출 없이 해당 사유를 저장한다") {
                        shouldNotThrowAny { action() }

                        fakeAiClient.requestedModels shouldBe emptyList()
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.summary shouldBe errorCode.message
                    }
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
                        val execution = async { processAiSummaryService.proceed(jobId) }
                        try {
                            withTimeout(5_000) { entered.await() }
                        } finally {
                            execution.cancelAndJoin()
                        }
                        execution.isCancelled shouldBe true
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

        Given("요약 접수 후 활성 상태가 변경된 작업") {
            lateinit var user: User
            lateinit var link: Link
            lateinit var key: UserProvider
            lateinit var model: AvailableModel
            var jobId = 0L

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                fakeAiClient.reset()
                user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
            }

            suspend fun enqueue(role: Role) {
                val context = insertAiContext(userId = user.id!!, role = role)
                key = context.first
                model = context.second
                service.updateLinkAiSummary(
                    loginId = user.id!!,
                    request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        ),
                )
                jobId = withTimeout(1_000) { commandChannel.receive() }
            }

            listOf(Role.NORMAL, Role.SUPER_ADMIN).forEach { role ->
                When("$role 작업의 제공자가 비활성화되면") {
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue(role)
                        val provider = aiProviderRepository.findById(key.providerId)!!
                        aiProviderRepository.update(provider.copyForTestActive(isActive = false))
                    }
                    val action = suspend { processAiSummaryService.proceed(jobId) }
                    Then("AI 호출 없이 작업과 링크가 실패하고 키와 요청 참조는 남는다") {
                        shouldNotThrowAny { action() }
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        aiJobRepository.findById(jobId)!!.requestModelId shouldBe model.id
                        linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                        val savedKey = userProviderRepository.findById(key.id!!)!!
                        savedKey.id shouldBe key.id
                        savedKey.apiKey shouldBe key.apiKey
                        savedKey.providerId shouldBe key.providerId
                        fakeAiClient.requestedModels shouldBe emptyList()
                    }
                }
                When("$role 작업의 유일한 모델이 비활성화되면") {
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue(role)
                        availableModelRepository.update(model.copyForTestActive(isActive = false))
                    }
                    val action = suspend { processAiSummaryService.proceed(jobId) }
                    Then("후보가 없어 AI 호출과 사용량 기록 없이 실패한다") {
                        shouldNotThrowAny { action() }
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                        fakeAiClient.requestedModels shouldBe emptyList()
                        dailyUsageRepository.findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = LocalDate.now(ZoneOffset.UTC),
                        ) shouldBe null
                    }
                }
            }

            When("기본 키의 첫 모델이 비활성이지만 활성 대체 모델이 있으면") {
                lateinit var fallback: AvailableModel
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    enqueue(Role.SUPER_ADMIN)
                    availableModelRepository.update(model.copyForTestActive(isActive = false))
                    fallback =
                        availableModelRepository.insert(
                            AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2),
                        )
                }
                val action = suspend { processAiSummaryService.proceed(jobId) }
                Then("활성 대체 모델로 완료하고 요청 모델 참조를 보존한다") {
                    shouldNotThrowAny { action() }
                    val saved = aiJobRepository.findById(jobId)!!
                    saved.status shouldBe AiJobStatus.C
                    saved.requestModelId shouldBe model.id
                    saved.responseModelId shouldBe fallback.id
                    fakeAiClient.requestedModels shouldBe listOf(fallback.model)
                }
            }

            listOf(false, true).forEach { deactivateProvider ->
                When("첫 호출 실패 중 ${if (deactivateProvider) "제공자" else "폴백 모델"}가 비활성화되면") {
                    lateinit var fallback: AvailableModel
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        enqueue(Role.SUPER_ADMIN)
                        fallback =
                            availableModelRepository.insert(
                                AiFixture.createRandomAvailableModelOf(providerId = key.providerId, priority = 2),
                            )
                        fakeAiClient.failuresByModel = mapOf(model.model to IllegalStateException("retry"))
                        fakeAiClient.beforeSummary = {
                            if (deactivateProvider) {
                                val provider = aiProviderRepository.findById(key.providerId)!!
                                aiProviderRepository.update(provider.copyForTestActive(isActive = false))
                            } else {
                                availableModelRepository.update(fallback.copyForTestActive(isActive = false))
                            }
                        }
                    }
                    val action = suspend { processAiSummaryService.proceed(jobId) }
                    Then("비활성 후보는 호출되지 않고 작업과 링크가 실패한다") {
                        shouldNotThrowAny { action() }
                        aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.F
                        linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.F
                        fakeAiClient.requestedModels.contains(fallback.model) shouldBe false
                        fakeAiClient.requestedModels.isNotEmpty() shouldBe true
                        if (deactivateProvider) fakeAiClient.requestedModels shouldBe listOf(model.model)
                    }
                }
            }
        }

        Given("AI 요약 worker 테스트") {
            lateinit var user: User
            lateinit var link: Link

            beforeTest {
                fakeAiClient.reset()
                user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
            }

            When("요약 생성 작업을 처리하면") {
                Then("작업, 링크, 사용량, 할 일을 반영한다") {
                    val folder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(user.id!!))
                    val (userProvider, model) = insertAiContext(userId = user.id!!)
                    fakeAiClient.usedTokens = 831
                    fakeAiClient.folderId = folder.id
                    fakeAiClient.tags = listOf("포털", "검색")
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = userProvider.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    val response = service.updateLinkAiSummary(user.id!!, request)
                    val jobId = withTimeout(1_000) { commandChannel.receive() }

                    processAiSummaryService.proceed(jobId)

                    val actualJob = aiJobRepository.findById(jobId)!!
                    val actualLink = linkRepository.findById(response.id)!!
                    val usage =
                        dailyUsageRepository.findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = userProvider.id,
                            modelId = model.id,
                            usageDate = LocalDate.now(ZoneOffset.UTC),
                        )
                    val todos = todoRepository.findAllByLinkId(response.id)

                    actualJob.status shouldBe AiJobStatus.C
                    actualJob.responseModelId shouldBe model.id
                    actualLink.status shouldBe LinkStatus.A
                    actualLink.folderId shouldBe folder.id
                    actualLink.title shouldBe "AI 제목"
                    actualLink.summary shouldBe "AI 요약"
                    actualLink.tags shouldBe listOf("포털", "검색")
                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                    fakeAiClient.requestedApiKeys shouldBe listOf("api-key")
                    usage!!.requests shouldBe 1
                    usage.tokens shouldBe 831
                    todos.map { it.title } shouldBe listOf("AI 할 일")
                }
            }

            When("AI 응답 folderId가 요청 사용자의 폴더가 아니면") {
                Then("요약은 성공 처리하고 링크 폴더를 null로 저장한다") {
                    val (userProvider, model) = insertAiContext(userId = user.id!!)
                    fakeAiClient.folderId = Long.MAX_VALUE
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = userProvider.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    service.updateLinkAiSummary(user.id!!, request)
                    val jobId = withTimeout(1_000) { commandChannel.receive() }

                    processAiSummaryService.proceed(jobId)

                    val actualJob = aiJobRepository.findById(jobId)!!
                    val actualLink = linkRepository.findById(link.id!!)!!

                    actualJob.status shouldBe AiJobStatus.C
                    actualLink.status shouldBe LinkStatus.A
                    actualLink.folderId shouldBe null
                    actualLink.summary shouldBe "AI 요약"
                }
            }

            When("요청의 고정 폴더와 AI가 선택한 폴더가 다르면") {
                var fixedFolderId = 0L
                var jobId = 0L

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    fixedFolderId = folderRepository.insert(FolderFixture.createValidUnsharedFolder(user.id!!)).id!!
                    fakeAiClient.folderId = folderRepository.insert(FolderFixture.createValidUnsharedFolder(user.id!!)).id
                    val (key, model) = insertAiContext(userId = user.id!!)
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            folderId = fixedFolderId,
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    service.updateLinkAiSummary(loginId = user.id!!, request = request)
                    jobId = withTimeout(1_000) { commandChannel.receive() }
                }

                val action = suspend { processAiSummaryService.proceed(jobId) }
                Then("완료된 링크에 고정 폴더를 유지한다") {
                    shouldNotThrowAny { action() }

                    aiJobRepository.findById(jobId)!!.status shouldBe AiJobStatus.C
                    linkRepository.findById(link.id!!)!!.folderId shouldBe fixedFolderId
                }
            }

            When("첫 번째 모델 시도가 성공하면") {
                Then("다음 모델을 시도하지 않는다") {
                    val (userProvider, model) = insertAiContext(userId = user.id!!, role = Role.SUPER_ADMIN)
                    availableModelRepository.insert(
                        AvailableModel(
                            providerId = userProvider.providerId,
                            model = "unused-model-${userProvider.id}",
                            priority = 2,
                            rpdLimit = 20,
                            tpdLimit = 2_000_000,
                            activeAt = RandomFixture.randomPastInstant(),
                        ),
                    )
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = userProvider.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    service.updateLinkAiSummary(user.id!!, request)
                    val jobId = withTimeout(1_000) { commandChannel.receive() }

                    processAiSummaryService.proceed(jobId)

                    val actualJob = aiJobRepository.findById(jobId)!!
                    val actualLink = linkRepository.findById(link.id!!)!!

                    actualJob.status shouldBe AiJobStatus.C
                    actualLink.status shouldBe LinkStatus.A
                    fakeAiClient.requestedModels shouldBe listOf(model.model)
                }
            }

            When("AI 응답 linkId가 작업 링크와 다르면") {
                lateinit var otherLink: Link
                var jobId = 0L

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val (userProvider, model) = insertAiContext(userId = user.id!!)
                    otherLink = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                    fakeAiClient.linkId = otherLink.id
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = userProvider.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    service.updateLinkAiSummary(user.id!!, request)
                    jobId = withTimeout(1_000) { commandChannel.receive() }
                }

                val action = suspend { processAiSummaryService.proceed(jobId) }
                Then("작업과 링크를 실패 처리하고 다른 링크는 보존한다") {
                    shouldNotThrowAny { action() }

                    val actualJob = aiJobRepository.findById(jobId)!!
                    val actualLink = linkRepository.findById(link.id!!)!!

                    actualJob.status shouldBe AiJobStatus.F
                    actualLink.status shouldBe LinkStatus.F
                    actualLink.title shouldBe "AI 생성 실패 - ${URI(link.url).host}"
                    actualLink.summary shouldBe ErrorCode.AI_SUMMARY_LINK_MISMATCH.message
                    val savedOtherLink = linkRepository.findById(otherLink.id!!)!!
                    savedOtherLink.summary shouldBe otherLink.summary
                    savedOtherLink.status shouldBe otherLink.status
                }
            }

            When("모든 모델 시도가 실패하면") {
                Then("마지막 시도 에러 메시지를 링크 요약에 남긴다") {
                    val (userProvider, model) = insertAiContext(userId = user.id!!, role = Role.SUPER_ADMIN)
                    val fallbackModel =
                        availableModelRepository.insert(
                            AvailableModel(
                                providerId = userProvider.providerId,
                                model = "fallback-model-${userProvider.id}",
                                priority = 2,
                                rpdLimit = 20,
                                tpdLimit = 2_000_000,
                                activeAt = RandomFixture.randomPastInstant(),
                            ),
                        )
                    fakeAiClient.failuresByModel =
                        mapOf(
                            model.model to IllegalStateException("first model failure"),
                            fallbackModel.model to IllegalStateException("fallback model failure"),
                        )
                    val request =
                        AiSummaryRequest(
                            id = link.id!!,
                            userProviderId = userProvider.id!!,
                            modelId = model.id!!,
                            url = link.url,
                        )
                    service.updateLinkAiSummary(user.id!!, request)
                    val jobId = withTimeout(1_000) { commandChannel.receive() }

                    processAiSummaryService.proceed(jobId)

                    val actualJob = aiJobRepository.findById(jobId)!!
                    val actualLink = linkRepository.findById(link.id!!)!!

                    actualJob.status shouldBe AiJobStatus.F
                    actualLink.status shouldBe LinkStatus.F
                    actualLink.title shouldBe "AI 생성 실패 - ${URI(link.url).host}"
                    actualLink.summary shouldBe "fallback model failure"
                    fakeAiClient.requestedModels shouldBe List(5) { listOf(model.model, fallbackModel.model) }.flatten()
                }
            }
        }
    })
