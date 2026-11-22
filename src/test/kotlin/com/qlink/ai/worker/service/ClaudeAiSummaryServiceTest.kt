package com.qlink.ai.worker.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummaryClientRequest
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.client.ClaudeAiClient
import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.service.GetAiProviderModelsService
import com.qlink.ai.service.PutAiUserProviderService
import com.qlink.ai.service.UpdateAiProviderActiveService
import com.qlink.ai.service.UpdateAiProviderService
import com.qlink.ai.service.UpdateAvailableModelActiveService
import com.qlink.ai.service.UpdateLinkAiSummaryService
import com.qlink.ai.worker.AiSummaryWorker
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.config.AbbreviatedSqlLogger
import com.qlink.config.MonitoringConfig
import com.qlink.di.externalModule
import com.qlink.di.repositoryModule
import com.qlink.di.serviceModule
import com.qlink.di.workerModule
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.notification.service.ScheduleTodoNotificationService
import com.qlink.support.BaseServiceTest
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.ClaudeHttpFixture
import com.qlink.support.fixture.ClaudeSummaryFixture
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import com.anthropic.core.http.HttpClient as AnthropicTransport

class ClaudeAiSummaryServiceTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val folders = koinGet<FolderRepository>()
        val jobs = koinGet<AiJobRepository>()
        val links = koinGet<LinkRepository>()
        val todos = koinGet<TodoRepository>()
        val usage = koinGet<DailyUsageRepository>()
        val cipher = koinGet<ApiKeyCipher>()
        val maxOutputTokens = RandomFixture.randomInt(512, 2048)
        val claudeSettings = MapApplicationConfig("ai.claude.maxOutputTokens" to maxOutputTokens.toString())
        val config = ServiceTestEnvironment.applicationConfig().mergeWith(claudeSettings)
        val s3Config = ServiceTestEnvironment.s3Config(environment = "dev")

        lateinit var http: ClaudeHttpFixture
        lateinit var app: KoinApplication
        lateinit var admin: User
        lateinit var owner: User
        lateinit var provider: AiProvider
        lateinit var model: AvailableModel
        lateinit var folder: Folder
        lateinit var fixture: ClaudeSummaryFixture
        lateinit var appender: ListAppender<ILoggingEvent>
        val loggers =
            listOf(ClaudeAiClient::class.java, AiSummaryWorker::class.java, AbbreviatedSqlLogger::class.java)
                .map { LoggerFactory.getLogger(it) as Logger }
        val sqlLoggerName = AbbreviatedSqlLogger::class.java.name
        val baseUrl = "https://api.anthropic.com"
        val otherUrl = "https://claude-alternative.example/${RandomFixture.randomId()}"
        val apiKey = "dummy-claude-key-${RandomFixture.randomId()}"

        suspend fun activateProvider() =
            app.koin.get<UpdateAiProviderActiveService>().updateAiProviderActive(
                loginId = admin.id!!,
                id = provider.id!!,
                request = UpdateAiActiveRequest(isActive = true),
            )

        suspend fun activateModel() =
            app.koin.get<UpdateAvailableModelActiveService>().updateAvailableModelActive(
                loginId = admin.id!!,
                id = model.id!!,
                request = UpdateAiActiveRequest(isActive = true),
            )

        suspend fun register(key: String = apiKey): Long =
            app.koin
                .get<PutAiUserProviderService>()
                .putAiUserProvider(
                    loginId = owner.id!!,
                    request = PutAiUserProviderRequest(providerId = provider.id!!, apiKey = key),
                ).id

        suspend fun submitSummary(
            userProviderId: Long,
            modelId: Long,
        ): AiJob {
            val response =
                app.koin.get<UpdateLinkAiSummaryService>().updateLinkAiSummary(
                    loginId = owner.id!!,
                    request =
                        AiSummaryRequest(
                            userProviderId = userProviderId,
                            modelId = modelId,
                            url = fixture.sourceUrl,
                            generateTodo = true,
                        ),
                )
            return jobs.findAllByLinkId(response.id).single()
        }

        suspend fun enqueue(): AiJob {
            activateProvider()
            activateModel()
            val registeredId = register()
            val selected =
                app.koin
                    .get<GetAiProviderModelsService>()
                    .getAiProviderModels(loginId = owner.id, isMine = true)
                    .single()
                    .models
                    .single()
            selected.userProviderId shouldBe registeredId
            return submitSummary(userProviderId = registeredId, modelId = selected.id)
        }

        suspend fun enqueueDefault(): AiJob {
            activateProvider()
            activateModel()
            val defaultProvider =
                keys.insert(
                    UserProvider(
                        userId = admin.id!!,
                        providerId = provider.id!!,
                        userRole = Role.SUPER_ADMIN,
                        apiKey = cipher.encrypt(apiKey),
                    ),
                )
            return submitSummary(userProviderId = defaultProvider.id!!, modelId = model.id!!)
        }

        suspend fun recordedUsage(job: AiJob) =
            usage.findByUserIdAndProviderIdAndUsageDate(
                userProviderId = job.userProviderId,
                modelId = job.requestModelId,
                usageDate = LocalDate.now(ZoneOffset.UTC),
            )

        fun assertProviderPayloadAbsent(text: String) {
            listOf(
                apiKey,
                ClaudeSummaryFixture.PAGE_MARKER,
                ClaudeSummaryFixture.PDF_MARKER,
                ClaudeHttpFixture.ERROR_MARKER,
                ClaudeHttpFixture.TRANSPORT_MARKER,
            ).forEach { text shouldNotContain it }
        }

        Given("운영 라우터에 등록된 Claude와 HTTP 응답 fixture") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                claudeSettings.put("ai.claude.maxOutputTokens", maxOutputTokens.toString())
                http = ClaudeHttpFixture()
                app =
                    koinApplication {
                        modules(
                            externalModule(config = config, s3Config = s3Config),
                            repositoryModule(),
                            serviceModule(),
                            workerModule(config),
                            module {
                                single<AnthropicTransport> { http.transport }
                                single { koinGet<TransactionRunner>() }
                                single { cipher }
                                single { koinGet<MonitoringConfig>() }
                                single { koinGet<PrometheusMeterRegistry>() }
                                single { koinGet<ScheduleTodoNotificationService>() }
                            },
                        )
                    }
                appender = ListAppender<ILoggingEvent>().apply { start() }
                loggers.forEach { it.addAppender(appender) }

                fixture =
                    ClaudeSummaryFixture(summary = "dummy-saved-summary ${RandomFixture.randomSentenceWithMax(100)}")
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                owner = users.insert(UserFixture.createRandomValidUser())
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false))
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                folder = folders.insert(FolderFixture.createFolderWith(ownerId = owner.id!!, name = otherUrl))
            }

            afterTest { (testCase, _) ->
                if (testCase.type != TestType.Test) return@afterTest
                loggers.forEach { logger -> logger.detachAppender(appender) }
                val events = appender.list.toList()
                appender.stop()
                app.close()
                http.close()
                events.forEach { event ->
                    val text = event.formattedMessage + event.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty()
                    assertProviderPayloadAbsent(text)
                    if (event.loggerName != sqlLoggerName) {
                        text shouldNotContain fixture.sourceUrl
                        text shouldNotContain fixture.summary
                    }
                }
            }

            When("관리자가 Claude 공급자와 모델을 활성화하고 공급자 활성화를 반복하면") {
                Then("운영 라우터가 Claude를 실행하고 저장소의 같은 공급자와 모델이 활성을 유지한다") {
                    val router = app.koin.get<AiClientRouter>()
                    router.isExecutable(AiProviderType.CLAUDE) shouldBe true
                    router.requireSupported(AiProviderType.CLAUDE) shouldBe app.koin.get<ClaudeAiClient>()

                    val first = activateProvider()
                    activateProvider() shouldBe first
                    activateModel()
                    providers
                        .findAll()
                        .filter { it.type == AiProviderType.CLAUDE }
                        .single()
                        .isActive shouldBe true
                    models.findById(model.id!!)!!.isActive shouldBe true
                }
            }

            When("모델 조회가 2xx를 반환하고 새 키로 재등록하면") {
                Then("같은 사용자 설정 한 건의 암호문을 갱신한다") {
                    activateProvider()
                    http.modelsStatus = HttpStatusCode.NoContent
                    val originalId = register()
                    val original = keys.findById(originalId)!!
                    (original.apiKey == apiKey) shouldBe false
                    cipher.decrypt(original.apiKey) shouldBe apiKey

                    val replacement = "dummy-claude-key-${RandomFixture.randomId()}"
                    register(replacement) shouldBe originalId
                    val saved = keys.findAllByUserId(owner.id!!).single()
                    saved.id shouldBe originalId
                    saved.providerId shouldBe provider.id
                    (saved.apiKey == original.apiKey) shouldBe false
                    cipher.decrypt(saved.apiKey) shouldBe replacement
                }
            }

            listOf(
                HttpStatusCode.Unauthorized to ErrorCode.AI_API_KEY_INVALID,
                HttpStatusCode.Forbidden to ErrorCode.AI_VENDOR_PERMISSION_DENIED,
                HttpStatusCode.BadRequest to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.NotFound to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.PayloadTooLarge to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.UnprocessableEntity to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.RequestTimeout to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                HttpStatusCode.ServiceUnavailable to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                HttpStatusCode.Found to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.TooManyRequests to ErrorCode.AI_VENDOR_RATE_LIMITED,
                HttpStatusCode.PaymentRequired to ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED,
                null to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
            ).forEach { (status, error) ->
                When("키 검증 결과가 ${status?.value ?: "전송 실패"}이면") {
                    var existingId: Long? = null
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        activateProvider()
                        existingId = if (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) register() else null
                        http.requests.clear()
                        http.modelsStatus = status
                        if (status == HttpStatusCode.PaymentRequired) {
                            http.errorBody = """{"error":{"type":"billing_error","message":"${ClaudeHttpFixture.ERROR_MARKER}"}}"""
                        }
                    }
                    val action = suspend { register("dummy-rejected-key-${RandomFixture.randomId()}") }
                    Then("API 오류로 매핑하고 새 키를 저장하지 않는다") {
                        shouldThrowWithMessage<BusinessException>(error.message) { action() }.errorCode shouldBe error
                        http.requests
                            .single()
                            .url.host shouldBe "api.anthropic.com"
                        val saved = keys.findByUserIdAndProviderId(userId = owner.id!!, providerId = provider.id!!)
                        saved?.id shouldBe existingId
                        saved?.let { cipher.decrypt(it.apiKey) shouldBe apiKey }
                    }
                }
            }

            listOf("", "/", "/v1", "/v1/", "/allowed/path").forEach { path ->
                When("공급자 기본 주소 경로가 '$path'이고 두 호출이 다른 호스트로 리다이렉트되면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        provider =
                            providers.update(
                                AiProvider(
                                    id = provider.id,
                                    type = provider.type,
                                    baseUrl = baseUrl + path,
                                    activeAt = RandomFixture.randomPastInstant(),
                                ),
                            )
                        job = enqueue()
                        http.requests.clear()
                        http.modelsStatus = HttpStatusCode.Found
                        http.messagesRedirect = "https://other.example/v1/messages"
                    }
                    val checkKey = suspend { register() }
                    val summarize = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("공식 엔드포인트에만 헤더를 전송하고 다른 호스트로 요청하지 않고 기존 키를 보존한다") {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.AI_VENDOR_REQUEST_REJECTED.message) { checkKey() }
                        shouldNotThrow<BusinessException> { summarize() }
                        links.findById(job.linkId)!!.summary shouldBe ErrorCode.AI_VENDOR_REQUEST_REJECTED.message
                        val prefix = if (path == "/allowed/path") path else ""
                        http.requests.map { it.method to it.url.toString() } shouldBe
                            listOf("GET" to "$baseUrl$prefix/v1/models?limit=1", "POST" to "$baseUrl$prefix/v1/messages")
                        keys.findAllByUserId(owner.id!!).single().id shouldBe job.userProviderId
                        http.requests.forEach { request ->
                            request.headers.getFirst("x-api-key") shouldBe apiKey
                            request.headers.getFirst("anthropic-version") shouldBe "2023-06-01"
                        }
                    }
                }
            }

            When("Claude가 같은 HTTPS 출처의 주소로 키 조회와 Messages를 리다이렉트하면") {
                lateinit var job: AiJob
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    app.koin.get<UpdateAiProviderService>().updateAiProvider(
                        loginId = admin.id!!,
                        id = provider.id!!,
                        request = UpdateAiProviderRequest(baseUrl = baseUrl),
                    )
                    http.modelsRedirect = "/redirected/models?limit=1"
                    job = enqueue()
                    http.messagesRedirect = "$baseUrl:443/redirected/messages"
                    http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = job.linkId, folderId = null))
                }
                val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                Then("두 요청이 같은 출처로 이동하고 키와 Messages 본문을 보존한다") {
                    shouldNotThrow<BusinessException> { action() }
                    http.requests.map { it.method to it.url.rawPath } shouldBe
                        listOf(
                            "GET" to "/v1/models",
                            "GET" to "/redirected/models",
                            "POST" to "/v1/messages",
                            "POST" to "/redirected/messages",
                        )
                    http.requests[1].url.rawQuery shouldBe "limit=1"
                    http.requests.forEach { it.headers.getFirst("x-api-key") shouldBe apiKey }
                    http.requests.last().body shouldBe http.requests[2].body
                    jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                }
            }

            When("Claude의 출력 토큰 설정이 유효하지 않으면") {
                lateinit var job: AiJob
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    claudeSettings.put("ai.claude.maxOutputTokens", "0")
                    job = enqueue()
                    http.requests.clear()
                }
                val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                Then("공급자 요청 없이 Claude 요약 오류를 저장한다") {
                    shouldNotThrow<BusinessException> { action() }
                    http.requests shouldBe emptyList()
                    links.findById(job.linkId)!!.summary shouldBe ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_UNAVAILABLE.message
                }
            }

            val prefix = "/gateway-${RandomFixture.randomId()}/claude"
            listOf(
                "" to "",
                "/v1" to "",
                "$prefix/" to prefix,
                ":443$prefix/v1/" to ":443$prefix",
                "$prefix//v1" to "$prefix/",
            ).forEach { (configured, expected) ->
                When("관리자가 Claude 기본 주소를 '$baseUrl$configured'로 저장하면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        app.koin.get<UpdateAiProviderService>().updateAiProvider(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiProviderRequest(baseUrl = baseUrl + configured),
                        )
                        job = enqueue()
                        http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = job.linkId, folderId = null))
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("저장된 주소에서 키를 검증하고 같은 주소에서 받은 요약을 저장한다") {
                        providers.findById(provider.id!!)!!.baseUrl shouldBe (baseUrl + configured).trimEnd('/')
                        shouldNotThrow<BusinessException> { action() }
                        val modelsUrl = URI("$baseUrl$expected/v1/models?limit=1")
                        val messagesUrl = URI("$baseUrl$expected/v1/messages")
                        http.sdkRequestUrls shouldBe listOf(modelsUrl, messagesUrl)
                        http.requests.map { it.method to it.url.rawPath } shouldBe
                            listOf("GET" to modelsUrl.rawPath, "POST" to messagesUrl.rawPath)
                        http.requests.forEach { request ->
                            request.headers.getFirst("x-api-key") shouldBe apiKey
                            request.headers.getFirst("anthropic-version") shouldBe "2023-06-01"
                        }
                        jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                        links.findById(job.linkId)!!.summary shouldBe fixture.summary
                    }
                }
            }

            listOf(null, "", "   ", "relative-path", "http://claude-source.example/page").forEach { requestedUrl ->
                When("Claude 요청의 requestedUrl이 '$requestedUrl'이면") {
                    var request: AiSummaryClientRequest? = null
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        request =
                            AiSummaryClientRequest(
                                providerType = AiProviderType.CLAUDE,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model.model,
                                prompt = RandomFixture.randomSentenceWithMax(100),
                                requestedUrl = requestedUrl,
                            )
                    }
                    val action = suspend { app.koin.get<ClaudeAiClient>().summarize(request!!) }
                    Then("외부 요청 없이 원문 주소 오류를 반환한다") {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID.message) { action() }
                        http.requests shouldBe emptyList()
                    }
                }
            }

            When("기본 Claude 공급자 작업의 저장된 원문 URL을 사용할 수 없으면") {
                lateinit var job: AiJob
                lateinit var fallbackModel: String
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val queued = enqueueDefault()
                    fallbackModel =
                        models
                            .insert(
                                AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority + 1),
                            ).model
                    job =
                        jobs.update(
                            AiJob(
                                id = queued.id,
                                linkId = queued.linkId,
                                userProviderId = queued.userProviderId,
                                requestModelId = queued.requestModelId,
                                requestedUrl = "relative-${RandomFixture.randomId()}",
                                prompt = queued.prompt,
                            ),
                        )
                }

                val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }

                Then("기존 후보 순회 후 원문 오류를 저장하고 HTTP 요청을 보내지 않는다") {
                    shouldNotThrow<BusinessException> { action() }
                    appender.list
                        .filter { it.formattedMessage.startsWith("[WORKER] Trial model=") }
                        .map { it.formattedMessage } shouldBe
                        List(5) {
                            listOf("[WORKER] Trial model=${model.model}", "[WORKER] Trial model=$fallbackModel")
                        }.flatten()
                    http.requests shouldBe emptyList()
                    jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.F
                    links.findById(job.linkId)!!.summary shouldBe ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID.message
                }
            }

            When("Claude 키 확인 도중 요청이 취소되면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    activateProvider()
                    http.holdResponse = true
                }
                Then("등록의 취소가 전파되고 공급자 키가 저장되지 않는다") {
                    coroutineScope {
                        val failure = CompletableDeferred<Throwable?>()
                        val registration = launch { failure.complete(runCatching { register() }.exceptionOrNull()) }
                        withTimeout(5000) { http.pendingRequest.await() }
                        registration.cancelAndJoin()
                        (failure.await() is CancellationException) shouldBe true
                    }
                    http.interrupted shouldBe true
                    keys.findAllByUserId(owner.id!!) shouldBe emptyList()
                }
            }

            When("사용자가 본인 Claude 설정의 모델 목록을 조회하면") {
                var keyId = 0L
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    activateProvider()
                    activateModel()
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                    val other = users.insert(UserFixture.createRandomValidUser())
                    keys.insert(AiFixture.createUserProviderOf(userId = other.id!!, providerId = provider.id!!))
                    keyId = register()
                }
                Then("저장소의 활성 모델만 본인 설정으로 반환한다") {
                    val response = app.koin.get<GetAiProviderModelsService>().getAiProviderModels(loginId = owner.id, isMine = true)
                    val active = models.findAllByProviderId(provider.id!!).filter { it.isActive }
                    response.single().models.map { it.id } shouldBe active.map { it.id }
                    response
                        .single()
                        .models
                        .map { it.userProviderId }
                        .distinct() shouldBe listOf(keyId)
                }
            }

            When("Claude 설정으로 다른 공급자 모델을 고르면") {
                lateinit var request: AiSummaryRequest
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    activateProvider()
                    val other = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI))
                    val otherModel = models.insert(AiFixture.createRandomAvailableModelOf(providerId = other.id!!))
                    request = AiSummaryRequest(userProviderId = register(), modelId = otherModel.id!!, url = fixture.sourceUrl)
                }
                val action =
                    suspend { app.koin.get<UpdateLinkAiSummaryService>().updateLinkAiSummary(loginId = owner.id!!, request = request) }
                Then("모델 오류를 반환하고 작업을 생성하지 않는다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NOT_FOUND.message) { action() }
                    jobs.findAllByStatus(AiJobStatus.P) shouldBe emptyList()
                }
            }

            listOf(true to 26, false to 14).forEach { (includeCache, tokens) ->
                When("선택한 Claude 모델이 유효한 최종 요약을 반환하면 (캐시 항목 포함=$includeCache)") {
                    lateinit var job: AiJob
                    lateinit var summaryJson: String
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        job = enqueue()
                        summaryJson = fixture.summaryJson(linkId = job.linkId, folderId = folder.id.takeIf { includeCache })
                        http.messagesBody = fixture.messagesResponse(finalText = summaryJson, includeCache = includeCache)
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("대기 작업의 원문·모델로 실행하고 최종 요약·액션·사용량을 저장한다") {
                        jobs.findById(job.id!!)!!.let {
                            it.status shouldBe AiJobStatus.P
                            it.requestedUrl shouldBe fixture.sourceUrl
                            it.requestModelId shouldBe model.id
                        }
                        links.findById(job.linkId)!!.status shouldBe LinkStatus.G
                        shouldNotThrow<BusinessException> { action() }

                        appender.list.any { it.formattedMessage.startsWith("INSERT INTO ai_jobs") } shouldBe true
                        verifyMessagesRequest(
                            request = http.requests.last(),
                            job = job,
                            model = model.model,
                            sourceUrl = fixture.sourceUrl,
                            maxOutputTokens = maxOutputTokens,
                        )
                        jobs.findById(job.id!!)!!.let {
                            it.status shouldBe AiJobStatus.C
                            it.responseModelId shouldBe model.id
                            it.response shouldBe summaryJson
                        }
                        links.findById(job.linkId)!!.let {
                            it.status shouldBe LinkStatus.A
                            it.title shouldBe fixture.title
                            it.summary shouldBe fixture.summary
                            it.tags shouldBe listOf(fixture.tag)
                            it.folderId shouldBe folder.id.takeIf { includeCache }
                            it.workModelId shouldBe model.id
                        }
                        val savedTodos = todos.findAllByLinkId(job.linkId).associateBy { it.title }
                        savedTodos.keys shouldBe setOf(fixture.scheduledAction, fixture.unscheduledAction)
                        savedTodos.getValue(fixture.scheduledAction).reminderAt?.epochSeconds shouldBe fixture.reminderAt.epochSeconds
                        savedTodos.getValue(fixture.unscheduledAction).reminderAt shouldBe null
                        recordedUsage(job)!!.let {
                            it.requests shouldBe 1
                            it.tokens shouldBe tokens
                        }
                    }
                }
            }

            When("Claude 요약 작업이 대기 중 만료되어 복구 대상이 되면") {
                lateinit var job: AiJob
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val pending = enqueue()
                    val expiredAt = jobs.now() - AiJob.STALE_AFTER - 1.minutes
                    job =
                        jobs.update(
                            AiJob(
                                id = pending.id,
                                linkId = pending.linkId,
                                userProviderId = pending.userProviderId,
                                requestModelId = pending.requestModelId,
                                requestedUrl = pending.requestedUrl,
                                prompt = pending.prompt,
                                requestedAt = expiredAt,
                            ),
                        )
                }
                val action = suspend { app.koin.get<AiSummaryWorker>().recover() }
                Then("작업과 링크를 복구 실패로 저장하고 공급자에 다시 요청하지 않는다") {
                    val initialLogCount = appender.list.size
                    shouldNotThrow<BusinessException> { action() }
                    appender.list
                        .drop(
                            initialLogCount,
                        ).any { it.formattedMessage.startsWith("UPDATE ai_jobs") } shouldBe
                        true
                    jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.F
                    links.findById(job.linkId)!!.let {
                        it.status shouldBe LinkStatus.F
                        it.summary shouldBe AiJob.DEFAULT_FAILURE_MESSAGE
                        assertProviderPayloadAbsent(it.summary!!)
                    }
                    http.requests
                        .single()
                        .url
                        .toString() shouldBe "${provider.baseUrl.removeSuffix("/v1")}/v1/models?limit=1"
                    recordedUsage(job) shouldBe null
                    todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                }
            }

            When("기본 Claude 공급자의 첫 모델이 503을 반환하면") {
                lateinit var job: AiJob
                lateinit var fallback: AvailableModel
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    app.koin.get<UpdateAiProviderService>().updateAiProvider(
                        loginId = admin.id!!,
                        id = provider.id!!,
                        request = UpdateAiProviderRequest(baseUrl = "$baseUrl$prefix//v1"),
                    )
                    job = enqueueDefault()
                    fallback =
                        models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority + 1))
                    http.nextMessageStatus = HttpStatusCode.ServiceUnavailable
                    http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = job.linkId, folderId = null))
                }
                val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                Then("SDK 재시도 없이 다음 모델을 호출하고 성공 모델의 사용량을 저장한다") {
                    shouldNotThrow<BusinessException> { action() }
                    http.requests.map { it.method to it.url.rawPath } shouldBe
                        List(2) { "POST" to "$prefix//v1/messages" }
                    val attemptedModels = http.requests.filter { it.method == "POST" }.map { summaryModel(it) }
                    attemptedModels shouldBe listOf(model.model, fallback.model)
                    jobs.findById(job.id!!)!!.let {
                        it.status shouldBe AiJobStatus.C
                        it.requestModelId shouldBe model.id
                        it.responseModelId shouldBe fallback.id
                    }
                    links.findById(job.linkId)!!.workModelId shouldBe fallback.id
                    recordedUsage(job) shouldBe null
                    usage
                        .findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = job.userProviderId,
                            modelId = fallback.id!!,
                            usageDate = LocalDate.now(ZoneOffset.UTC),
                        )!!
                        .requests shouldBe 1
                }
            }

            listOf(
                HttpStatusCode.Unauthorized to ErrorCode.AI_API_KEY_INVALID,
                HttpStatusCode.Forbidden to ErrorCode.AI_VENDOR_PERMISSION_DENIED,
                HttpStatusCode.NotFound to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                HttpStatusCode.TooManyRequests to ErrorCode.AI_VENDOR_RATE_LIMITED,
                HttpStatusCode.PaymentRequired to ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED,
            ).forEach { (status, error) ->
                When("기본 Claude 모델이 ${status.value} 오류를 반환하면") {
                    lateinit var job: AiJob
                    lateinit var fallback: AvailableModel
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        job = enqueueDefault()
                        fallback =
                            models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority + 1))
                        http.messagesStatus = status
                        if (error == ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED) {
                            http.errorBody =
                                """{"error":{"type":"billing_error","message":"${ClaudeHttpFixture.ERROR_MARKER}"}}"""
                        }
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("기존 후보 순회를 유지하고 정제된 실패만 저장한다") {
                        shouldNotThrow<BusinessException> { action() }
                        http.requests.filter { it.method == "POST" }.map { summaryModel(it) } shouldBe
                            List(5) { listOf(model.model, fallback.model) }.flatten()
                        jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.F
                        links.findById(job.linkId)!!.summary shouldBe error.message
                        recordedUsage(job) shouldBe null
                        todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                    }
                }
            }

            When("Claude 요약 도중 워커가 취소되면") {
                lateinit var job: AiJob
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    job = enqueue()
                    http.holdResponse = true
                }
                Then("공급자 호출을 취소하고 완료나 실패를 저장하지 않는다") {
                    coroutineScope {
                        val worker = launch { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                        withTimeout(5000) { http.pendingRequest.await() }
                        worker.cancelAndJoin()
                    }
                    http.interrupted shouldBe true
                    jobs.findById(job.id!!)!!.let {
                        it.status shouldBe AiJobStatus.P
                        (it.startedAt != null) shouldBe true
                    }
                    links.findById(job.linkId)!!.status shouldBe LinkStatus.G
                    recordedUsage(job) shouldBe null
                    todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                }
            }

            val invalidMessages: Map<String, (AiJob) -> String> =
                linkedMapOf(
                    "다른 URL" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), fetchUrl = otherUrl) },
                    "가져온 결과의 URL 불일치" to {
                        fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), resultUrl = otherUrl)
                    },
                    "다른 도구 호출 ID" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), resultId = "wrong") },
                    "가져오기 오류" to {
                        fixture.messagesResponse(
                            finalText = fixture.summaryJson(it.linkId, null),
                            fetchStatus = "web_fetch_tool_result_error",
                        )
                    },
                    "SDK 형식의 가져오기 오류" to {
                        fixture.messagesResponse(
                            finalText = fixture.summaryJson(it.linkId, null),
                            fetchStatus = "web_fetch_tool_result_error",
                            fetchErrorCode = "url_not_accessible",
                        )
                    },
                    "가져오기 결과 누락" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), fetchStatus = null) },
                    "빈 최종 텍스트" to { fixture.messagesResponse(finalText = " ") },
                    "refusal" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "refusal") },
                    "max_tokens" to
                        { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "max_tokens") },
                    "pause_turn" to
                        { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "pause_turn") },
                    "깨진 JSON" to { fixture.messagesResponse(finalText = "{${ClaudeSummaryFixture.PAGE_MARKER}") },
                    "깨진 SDK 응답" to { "{${ClaudeSummaryFixture.PAGE_MARKER}" },
                    "잘못된 토큰 사용량" to {
                        fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), inputTokens = Int.MAX_VALUE.toLong())
                    },
                    "링크 ID 누락" to { fixture.messagesResponse(finalText = fixture.summaryJson(null, null)) },
                    "링크 ID 불일치" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId + 1, null)) },
                )
            invalidMessages.forEach { (scenario, body) ->
                When("Claude 응답이 '$scenario'이면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        job = enqueue()
                        http.messagesBody = body(job)
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("실패 사유만 저장하고 요약·액션·사용량을 반영하지 않는다") {
                        shouldNotThrow<BusinessException> { action() }
                        appender.list.any { it.formattedMessage.startsWith("UPDATE ai_jobs") } shouldBe true
                        val error =
                            when (scenario) {
                                "다른 URL", "가져온 결과의 URL 불일치" -> ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH
                                "다른 도구 호출 ID", "가져오기 오류", "SDK 형식의 가져오기 오류", "가져오기 결과 누락" -> ErrorCode.AI_SUMMARY_FETCH_FAILED
                                "빈 최종 텍스트" -> ErrorCode.AI_EMPTY_RESPONSE
                                "refusal" -> ErrorCode.AI_SUMMARY_REFUSED
                                "max_tokens" -> ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED
                                "pause_turn" -> ErrorCode.AI_SUMMARY_INCOMPLETE
                                "깨진 JSON", "깨진 SDK 응답" -> ErrorCode.AI_SUMMARY_RESPONSE_INVALID
                                "잘못된 토큰 사용량" -> ErrorCode.AI_SUMMARY_USAGE_INVALID
                                else -> ErrorCode.AI_SUMMARY_LINK_MISMATCH
                            }
                        jobs.findById(job.id!!)!!.let {
                            it.status shouldBe AiJobStatus.F
                            it.response shouldBe null
                            it.responseModelId shouldBe null
                        }
                        links.findById(job.linkId)!!.let {
                            it.status shouldBe LinkStatus.F
                            it.summary shouldBe error.message
                            assertProviderPayloadAbsent(it.summary!!)
                        }
                        recordedUsage(job) shouldBe null
                        todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                    }
                }
            }
        }
    })

private fun verifyMessagesRequest(
    request: ClaudeHttpFixture.Request,
    job: AiJob,
    model: String,
    sourceUrl: String,
    maxOutputTokens: Int,
) {
    val body = Json.parseToJsonElement(request.body).jsonObject
    body.getValue("system").jsonPrimitive.content shouldBe AiSummarySpec.systemInstruction
    body.getValue("model").jsonPrimitive.content shouldBe model
    body.getValue("max_tokens").jsonPrimitive.int shouldBe maxOutputTokens
    val userMessage =
        body
            .getValue("messages")
            .jsonArray
            .single()
            .jsonObject
    userMessage.getValue("role").jsonPrimitive.content shouldBe "user"
    userMessage.getValue("content").jsonPrimitive.content shouldContain sourceUrl
    userMessage.getValue("content").jsonPrimitive.content shouldBe job.prompt
    userMessage
        .getValue("content")
        .jsonPrimitive.content
        .split(sourceUrl)
        .size shouldBe 2

    val tool =
        body
            .getValue("tools")
            .jsonArray
            .single()
            .jsonObject
    tool.getValue("type").jsonPrimitive.content shouldBe "web_fetch_20250910"
    tool.getValue("name").jsonPrimitive.content shouldBe "web_fetch"
    tool.getValue("max_uses").jsonPrimitive.int shouldBe 1
    tool
        .getValue("allowed_domains")
        .jsonArray
        .single()
        .jsonPrimitive.content shouldBe "claude-source.example"
    tool
        .getValue("citations")
        .jsonObject
        .getValue("enabled")
        .jsonPrimitive.boolean shouldBe false

    val format =
        body
            .getValue("output_config")
            .jsonObject
            .getValue("format")
            .jsonObject
    format.getValue("type").jsonPrimitive.content shouldBe "json_schema"
    val schema = format.getValue("schema").jsonObject
    schema.getValue("additionalProperties").jsonPrimitive.boolean shouldBe false
    schema
        .getValue("required")
        .jsonArray
        .map { it.jsonPrimitive.contentOrNull }
        .toSet() shouldBe
        setOf("id", "title", "summary", "folderId", "tags", "todos")
    val properties = schema.getValue("properties").jsonObject
    properties
        .getValue("id")
        .jsonObject
        .getValue("type")
        .jsonPrimitive.content shouldBe "integer"
    properties
        .getValue("folderId")
        .jsonObject
        .getValue("type")
        .jsonArray
        .map { it.jsonPrimitive.content } shouldBe
        listOf("integer", "null")
    val todo =
        properties
            .getValue("todos")
            .jsonObject
            .getValue("items")
            .jsonObject
    todo.getValue("additionalProperties").jsonPrimitive.boolean shouldBe false
    todo
        .getValue("required")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .toSet() shouldBe setOf("title", "reminderAt")
    val reminder =
        todo
            .getValue("properties")
            .jsonObject
            .getValue("reminderAt")
            .jsonObject
    reminder.getValue("type").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("string", "null")
    reminder.getValue("format").jsonPrimitive.content shouldBe "date-time"
}

private fun summaryModel(request: ClaudeHttpFixture.Request): String =
    Json
        .parseToJsonElement(request.body)
        .jsonObject
        .getValue("model")
        .jsonPrimitive.content
