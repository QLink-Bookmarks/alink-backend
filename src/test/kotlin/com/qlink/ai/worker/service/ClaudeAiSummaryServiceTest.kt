package com.qlink.ai.worker.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.anthropic.client.AnthropicClient
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.client.ClaudeCurlClient
import com.qlink.ai.client.ClaudeSdkClient
import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
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
import com.qlink.config.ClaudeConfig
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
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ApplicationConfigValue
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import kotlin.time.Duration.Companion.minutes
import com.anthropic.core.http.HttpClient as AnthropicTransport
import io.ktor.client.HttpClient as KtorTransport

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
        val applicationConfig = ServiceTestEnvironment.applicationConfig()
        lateinit var claudeSettings: ApplicationConfig
        val config =
            object : ApplicationConfig by applicationConfig {
                override fun propertyOrNull(path: String): ApplicationConfigValue? =
                    if (path.startsWith("ai.claude.")) {
                        claudeSettings.propertyOrNull(path)
                    } else {
                        applicationConfig.propertyOrNull(path)
                    }
            }
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
            listOf(ClaudeSdkClient::class.java, ClaudeCurlClient::class.java, AiSummaryWorker::class.java, AbbreviatedSqlLogger::class.java)
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

        Given("Claude 클라이언트 선택 설정") {
            listOf("invalid", "TRUE", "[true]", "\"\"").forEach { value ->
                When("ai.claude.useSdk에 $value 값이 지정되면") {
                    val start = {
                        val invalid = HoconApplicationConfig(ConfigFactory.parseString("ai.claude.useSdk = $value"))
                        externalModule(config = invalid, s3Config = s3Config)
                    }
                    Then("설정 키를 명시한 오류로 기동을 중단한다") {
                        val error = ErrorCode.CONFIG_CLAUDE_USE_SDK_INVALID
                        shouldThrowWithMessage<BusinessException>(error.message) { start() }.errorCode shouldBe error
                    }
                }
            }
        }

        listOf(true, false).forEach { useSdk ->
            Given("운영 라우터에 등록된 Claude와 HTTP 응답 fixture (SDK=$useSdk)") {
                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    claudeSettings =
                        MapApplicationConfig(
                            "ai.claude.maxOutputTokens" to maxOutputTokens.toString(),
                            "ai.claude.sdkEndpoint" to baseUrl,
                        )
                    if (!useSdk) {
                        (claudeSettings as MapApplicationConfig).put("ai.claude.useSdk", "false")
                    }
                    http = ClaudeHttpFixture()
                    app =
                        koinApplication {
                            modules(
                                externalModule(config = config, s3Config = s3Config),
                                repositoryModule(),
                                serviceModule(),
                                workerModule(config),
                                module {
                                    if (useSdk) {
                                        single<AnthropicTransport> { http.transport }
                                    } else {
                                        single<KtorTransport>(named<ClaudeCurlClient>()) { http.ktorTransport }
                                    }
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
                    val providerFixture = AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false)
                    val configured =
                        if (useSdk) {
                            providerFixture
                        } else {
                            providerFixture.changeBaseUrl(
                                baseUrl = "$baseUrl/v1",
                                now = RandomFixture.randomPastInstant(),
                            )
                        }
                    provider = providers.insert(configured)
                    model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                    folder = folders.insert(FolderFixture.createFolderWith(ownerId = owner.id!!, name = otherUrl))
                }

                afterTest { (testCase, _) ->
                    if (testCase.type != TestType.Test) {
                        return@afterTest
                    }
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
                        val selected = router.requireSupported(AiProviderType.CLAUDE)
                        app.koin.get<ClaudeConfig>().useSdk shouldBe useSdk
                        app.koin.getAll<AiClient>().none { it.providerType == AiProviderType.CLAUDE } shouldBe true
                        if (useSdk) {
                            selected shouldBeSameInstanceAs app.koin.get<ClaudeSdkClient>()
                            app.koin.getOrNull<ClaudeCurlClient>() shouldBe null
                            app.koin.getOrNull<KtorTransport>(named<ClaudeCurlClient>()) shouldBe null
                        } else {
                            selected shouldBeSameInstanceAs app.koin.get<ClaudeCurlClient>()
                            app.koin.getOrNull<ClaudeSdkClient>() shouldBe null
                            app.koin.getOrNull<AnthropicClient>() shouldBe null
                            app.koin.getOrNull<AnthropicTransport>() shouldBe null
                            app.koin.getOrNull<ExecutorCoroutineDispatcher>(named<ClaudeSdkClient>()) shouldBe null
                        }

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

                When("선택한 Claude 전송을 사용한 Koin이 종료되면") {
                    Then("활성 전송과 SDK 실행기를 닫는다") {
                        var transportClosed = false
                        val production =
                            koinApplication {
                                val sdkFixture =
                                    module {
                                        single<AnthropicTransport> {
                                            object : AnthropicTransport by http.transport {
                                                override fun close() {
                                                    http.transport.close()
                                                    transportClosed = true
                                                }
                                            }
                                        }
                                    }
                                modules(externalModule(config = config, s3Config = s3Config))
                                if (useSdk) {
                                    modules(sdkFixture)
                                }
                            }
                        val koin = production.koin
                        if (useSdk) {
                            val dispatcher =
                                try {
                                    koin.get<ClaudeSdkClient>()
                                    koin.get<ExecutorCoroutineDispatcher>(named<ClaudeSdkClient>()).executor as ExecutorService
                                } finally {
                                    production.close()
                                }
                            transportClosed shouldBe true
                            dispatcher.isShutdown shouldBe true
                        } else {
                            val transportJob =
                                try {
                                    koin.get<ClaudeCurlClient>()
                                    koin.get<KtorTransport>(named<ClaudeCurlClient>()).coroutineContext[Job]!!
                                } finally {
                                    production.close()
                                }
                            withTimeout(5_000) { transportJob.join() }
                            transportJob.isCompleted shouldBe true
                        }
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
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            activateProvider()
                            existingId =
                                if (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                                    register()
                                } else {
                                    null
                                }
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

                if (!useSdk) {
                    When("Ktor 요청 파이프라인에서 내부 오류가 나면") {
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            activateProvider()
                            http.failKtorPipeline = true
                        }
                        val action = suspend { register() }
                        Then("공통 내부 오류를 반환하고 키를 저장하지 않는다") {
                            val error = ErrorCode.COMMON_INTERNAL_SERVER_ERROR
                            shouldThrowWithMessage<BusinessException>(error.message) { action() }.errorCode shouldBe error
                            http.requests shouldBe emptyList()
                            keys.findAllByUserId(owner.id!!) shouldBe emptyList()
                        }
                    }
                }

                When("Claude 키 검증과 요약이 다른 호스트로 리다이렉트되면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        job = enqueue()
                        http.requests.clear()
                        http.modelsRedirect = "https://other.example/v1/models"
                        http.messagesRedirect = "https://other.example/v1/messages"
                    }
                    val checkKey = suspend { register() }
                    val summarize = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("설정한 HTTPS 출처에만 키를 전송하고 기존 키를 보존한다") {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.AI_VENDOR_REQUEST_REJECTED.message) { checkKey() }
                        shouldNotThrow<BusinessException> { summarize() }
                        links.findById(job.linkId)!!.summary shouldBe ErrorCode.AI_VENDOR_REQUEST_REJECTED.message
                        http.requests.map { it.method to it.url.toString() } shouldBe
                            listOf("GET" to "$baseUrl/v1/models?limit=1", "POST" to "$baseUrl/v1/messages")
                        keys.findAllByUserId(owner.id!!).single().id shouldBe job.userProviderId
                        http.requests.forEach { request ->
                            request.headers.getFirst("x-api-key") shouldBe apiKey
                            request.headers.getFirst("anthropic-version") shouldBe "2023-06-01"
                        }
                    }
                }

                When("Claude가 같은 HTTPS 출처의 주소로 키 조회와 Messages를 리다이렉트하면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
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

                When("Claude가 같은 출처로 307을 반환하고 리다이렉트 본문을 보류하면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        job = enqueue()
                        http.requests.clear()
                        http.messagesRedirect = "/redirected/messages"
                        http.holdRedirectBody = true
                        http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = job.linkId, folderId = null))
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("본문을 기다리지 않고 한 번만 재전송하여 요약을 완료한다") {
                        coroutineScope {
                            val worker = async { action() }
                            try {
                                withTimeout(5000) { http.heldRequests.receive() }.url.rawPath shouldBe "/v1/messages"
                                withTimeout(5000) { worker.await() }
                            } finally {
                                http.releaseResponses()
                            }
                        }
                        http.requests.map { it.url.rawPath } shouldBe listOf("/v1/messages", "/redirected/messages")
                        jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                        links.findById(job.linkId)!!.summary shouldBe fixture.summary
                    }
                }

                listOf(
                    "",
                    "ai.claude.maxOutputTokens = invalid",
                    "ai.claude.maxOutputTokens = [1000]",
                    "ai.claude.maxOutputTokens = 0",
                ).forEach { setting ->
                    When("Claude 출력 토큰 설정이 누락되거나 잘못되면 ($setting)") {
                        lateinit var job: AiJob
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            claudeSettings = HoconApplicationConfig(ConfigFactory.parseString(setting))
                            job = enqueue()
                            http.requests.clear()
                        }
                        val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                        Then("Claude 요약만 실패하고 기존 공급자 라우터와 Claude 키 검증은 동작한다") {
                            val router = app.koin.get<AiClientRouter>()
                            router.requireSupported(AiProviderType.OPENAI).providerType shouldBe AiProviderType.OPENAI
                            router.requireSupported(AiProviderType.GEMINI).providerType shouldBe AiProviderType.GEMINI
                            shouldNotThrow<BusinessException> { action() }
                            http.requests shouldBe emptyList()
                            links.findById(job.linkId)!!.summary shouldBe ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_UNAVAILABLE.message
                            shouldNotThrow<BusinessException> { register() }
                            http.requests.single().method shouldBe "GET"
                        }
                    }
                }

                val prefix = "/gateway-${RandomFixture.randomId()}/claude"
                if (useSdk) {
                    When("Claude SDK 주소가 허용되지 않은 HTTPS 호스트면") {
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            claudeSettings = MapApplicationConfig("ai.claude.sdkEndpoint" to otherUrl)
                            activateProvider()
                        }
                        val action = suspend { register() }
                        Then("요청을 보내거나 키를 저장하지 않는다") {
                            shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_BASE_URL_INVALID.message) { action() }
                            http.requests shouldBe emptyList()
                            keys.findAllByUserId(owner.id!!) shouldBe emptyList()
                        }
                    }

                    listOf(
                        "" to "",
                        "/" to "",
                        "/v1" to "/v1",
                        "/v1/" to "/v1",
                        "$prefix/" to prefix,
                        "$prefix/v1" to "$prefix/v1",
                        ":443$prefix/v1/" to ":443$prefix/v1",
                        "$prefix//" to "$prefix/",
                        "$prefix//v1" to "$prefix//v1",
                        "$prefix/v1//" to "$prefix/v1/",
                    ).forEach { (configured, expected) ->
                        When("Claude SDK 주소를 '$baseUrl$configured'로 구성하고 DB에 다른 주소를 저장하면") {
                            lateinit var job: AiJob
                            val storedUrl = "$baseUrl/stored-${RandomFixture.randomId()}/v1"
                            beforeTest {
                                if (it.type != TestType.Test) {
                                    return@beforeTest
                                }
                                claudeSettings =
                                    MapApplicationConfig(
                                        "ai.claude.maxOutputTokens" to maxOutputTokens.toString(),
                                        "ai.claude.sdkEndpoint" to baseUrl + configured,
                                    )
                                app.koin.get<UpdateAiProviderService>().updateAiProvider(
                                    loginId = admin.id!!,
                                    id = provider.id!!,
                                    request = UpdateAiProviderRequest(baseUrl = storedUrl),
                                )
                                job = enqueue()
                                val summary = fixture.summaryJson(linkId = job.linkId, folderId = null)
                                http.messagesBody = fixture.messagesResponse(finalText = summary)
                            }
                            val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                            Then("DB 값을 보존하고 공유 SDK 연결로 설정한 주소에만 요청을 보낸다") {
                                providers.findById(provider.id!!)!!.baseUrl shouldBe storedUrl
                                shouldNotThrow<BusinessException> { action() }
                                val modelsUrl = URI("$baseUrl$expected/v1/models?limit=1")
                                val messagesUrl = URI("$baseUrl$expected/v1/messages")
                                http.sdkRequestUrls shouldBe listOf(modelsUrl, messagesUrl)
                                http.requests.map { it.method to it.url.rawPath } shouldBe
                                    listOf("GET" to modelsUrl.rawPath, "POST" to messagesUrl.rawPath)
                                http.requests
                                    .map { it.connectionPort }
                                    .distinct()
                                    .size shouldBe 1
                                http.requests.forEach { request ->
                                    request.headers["x-api-key"] shouldBe listOf(apiKey)
                                    request.headers.getFirst("anthropic-version") shouldBe "2023-06-01"
                                }
                                jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                                links.findById(job.linkId)!!.summary shouldBe fixture.summary
                            }
                        }
                    }
                } else {
                    listOf("/v1/", "$prefix//v1", "$prefix//v1/", ":443$prefix/custom").forEach { storedPath ->
                        When("Ktor 모드에서 SDK 주소와 DB 주소가 다르면 ($storedPath)") {
                            lateinit var job: AiJob
                            val storedUrl = baseUrl + storedPath
                            beforeTest {
                                if (it.type != TestType.Test) {
                                    return@beforeTest
                                }
                                claudeSettings =
                                    MapApplicationConfig(
                                        "ai.claude.maxOutputTokens" to maxOutputTokens.toString(),
                                        "ai.claude.sdkEndpoint" to otherUrl,
                                    )
                                app.koin.get<UpdateAiProviderService>().updateAiProvider(
                                    loginId = admin.id!!,
                                    id = provider.id!!,
                                    request = UpdateAiProviderRequest(baseUrl = storedUrl),
                                )
                                job = enqueue()
                                val summary = fixture.summaryJson(linkId = job.linkId, folderId = null)
                                http.messagesBody = fixture.messagesResponse(finalText = summary)
                            }
                            val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                            Then("DB에 저장된 경로와 요청별 키를 사용한다") {
                                providers.findById(provider.id!!)!!.baseUrl shouldBe storedUrl.trimEnd('/')
                                shouldNotThrow<BusinessException> { action() }
                                val path = URI(storedUrl).rawPath.removeSuffix("/")
                                http.curlRequestUrls.map { it.rawPath to it.rawQuery } shouldBe
                                    listOf("$path/models" to "limit=1", "$path/messages" to null)
                                http.requests.map { it.url.rawPath } shouldBe listOf("$path/models", "$path/messages")
                                http.requests.forEach { request ->
                                    request.headers["x-api-key"] shouldBe listOf(apiKey)
                                    request.headers.getFirst("anthropic-version") shouldBe "2023-06-01"
                                }
                                http.sdkRequestUrls shouldBe emptyList()
                                jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                                links.findById(job.linkId)!!.summary shouldBe fixture.summary
                            }
                        }
                    }
                }

                When("공유 Claude 클라이언트에서 서로 다른 사용자 키의 두 요청이 겹치면") {
                    val summaryKey = "dummy-second-key-${RandomFixture.randomId()}"
                    val linkId = RandomFixture.randomId()
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        http.holdResponse = true
                        http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = linkId, folderId = null))
                    }
                    Then("ModelList와 Messages 각각에 해당 요청의 키만 전송한다") {
                        coroutineScope {
                            val adapter = app.koin.get<AiClientRouter>().requireSupported(AiProviderType.CLAUDE)
                            val validate =
                                async { adapter.validateApiKey(AiApiKeyValidationRequest(baseUrl = provider.baseUrl, apiKey = apiKey)) }
                            val summarize =
                                async {
                                    adapter.summarize(
                                        AiSummaryClientRequest(
                                            providerType = AiProviderType.CLAUDE,
                                            baseUrl = provider.baseUrl,
                                            apiKey = summaryKey,
                                            model = model.model,
                                            prompt = fixture.sourceUrl,
                                            requestedUrl = fixture.sourceUrl,
                                        ),
                                    )
                                }
                            try {
                                val sent = withTimeout(5000) { List(2) { http.heldRequests.receive() } }
                                sent.associate { it.method to it.headers["x-api-key"] } shouldBe
                                    mapOf("GET" to listOf(apiKey), "POST" to listOf(summaryKey))
                            } finally {
                                http.releaseResponses()
                            }
                            validate.await()
                            summarize.await().linkId shouldBe linkId
                        }
                    }
                }

                listOf(null, "", "   ", "relative-path", "http://claude-source.example/page").forEach { requestedUrl ->
                    When("Claude 요청의 requestedUrl이 '$requestedUrl'이면") {
                        var request: AiSummaryClientRequest? = null
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
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
                        val action =
                            suspend {
                                app.koin
                                    .get<AiClientRouter>()
                                    .requireSupported(AiProviderType.CLAUDE)
                                    .summarize(request!!)
                            }
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
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
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

                listOf(false, true).forEach { redirected ->
                    When("Claude 키 확인 도중 요청이 취소되면 (리다이렉트=$redirected)") {
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            activateProvider()
                            http.holdResponse = true
                            if (redirected) {
                                http.modelsRedirect = "/redirected/models"
                                http.heldPath = "/redirected/models"
                            }
                        }
                        Then("등록의 취소가 전파되고 공급자 키가 저장되지 않는다") {
                            coroutineScope {
                                val failure = CompletableDeferred<Throwable?>()
                                val registration = launch { failure.complete(runCatching { register() }.exceptionOrNull()) }
                                try {
                                    val held = withTimeout(5000) { http.heldRequests.receive() }
                                    held.method shouldBe "GET"
                                    held.url.rawPath shouldBe
                                        if (redirected) {
                                            "/redirected/models"
                                        } else {
                                            "/v1/models"
                                        }
                                    withTimeout(3000) { registration.cancelAndJoin() }
                                    (failure.await() is CancellationException) shouldBe true
                                } finally {
                                    http.releaseResponses()
                                }
                            }
                            http.requests.size shouldBe
                                if (redirected) {
                                    2
                                } else {
                                    1
                                }
                            keys.findAllByUserId(owner.id!!) shouldBe emptyList()
                        }
                    }
                }

                When("사용자가 본인 Claude 설정의 모델 목록을 조회하면") {
                    var keyId = 0L
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
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
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
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
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
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

                listOf("claude-sonnet-4-6", "claude-haiku-4-5-20251001").forEach { modelId ->
                    When("공개 모델 ID $modelId 사용 중 알 수 없는 중간 블록이 추가되면") {
                        lateinit var job: AiJob
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            model =
                                models.insert(
                                    AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false, model = modelId),
                                )
                            job = enqueue()
                            http.messagesBody =
                                fixture.messagesResponse(
                                    finalText = fixture.summaryJson(linkId = job.linkId, folderId = null),
                                    editContent = { it.dropLast(1) + unknownClaudeBlock + it.last() },
                                )
                        }
                        val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                        Then("원래 모델 ID로 실행하고 가져오기 뒤의 구조화 요약을 저장한다") {
                            shouldNotThrow<BusinessException> { action() }
                            summaryModel(http.requests.last()) shouldBe modelId
                            links.findById(job.linkId)!!.summary shouldBe fixture.summary
                            jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                        }
                    }
                }

                When("Claude가 원문 문서를 text/plain으로 가져오면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        job = enqueue()
                        http.messagesBody =
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(linkId = job.linkId, folderId = null),
                                editContent = { blocks ->
                                    blocks.editFetchDocument { document ->
                                        val source =
                                            buildJsonObject {
                                                put("type", "text")
                                                put("media_type", "text/plain")
                                                put("data", ClaudeSummaryFixture.PAGE_MARKER)
                                            }
                                        JsonObject(document + ("source" to source))
                                    }
                                },
                            )
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("성공 결과 뒤의 요약과 사용량을 저장한다") {
                        shouldNotThrow<BusinessException> { action() }
                        jobs.findById(job.id!!)!!.status shouldBe AiJobStatus.C
                        links.findById(job.linkId)!!.summary shouldBe fixture.summary
                        recordedUsage(job)!!.tokens shouldBe 26
                    }
                }

                When("Claude 요약 작업이 대기 중 만료되어 복구 대상이 되면") {
                    lateinit var job: AiJob
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
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
                            .toString() shouldBe "$baseUrl/v1/models?limit=1"
                        recordedUsage(job) shouldBe null
                        todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                    }
                }

                When("기본 Claude 공급자의 첫 모델이 503을 반환하면") {
                    lateinit var job: AiJob
                    lateinit var fallback: AvailableModel
                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        claudeSettings =
                            MapApplicationConfig(
                                "ai.claude.maxOutputTokens" to maxOutputTokens.toString(),
                                "ai.claude.sdkEndpoint" to "$baseUrl$prefix//",
                            )
                        job = enqueueDefault()
                        fallback =
                            models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority + 1))
                        http.nextMessageStatus = HttpStatusCode.ServiceUnavailable
                        http.messagesBody = fixture.messagesResponse(finalText = fixture.summaryJson(linkId = job.linkId, folderId = null))
                    }
                    val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                    Then("클라이언트 재시도 없이 다음 모델을 호출하고 성공 모델의 사용량을 저장한다") {
                        shouldNotThrow<BusinessException> { action() }
                        http.requests.map { it.method to it.url.rawPath } shouldBe
                            List(2) {
                                "POST" to
                                    if (useSdk) {
                                        "$prefix//v1/messages"
                                    } else {
                                        "/v1/messages"
                                    }
                            }
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
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            job = enqueueDefault()
                            fallback =
                                models.insert(
                                    AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority + 1),
                                )
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

                listOf(false, true).forEach { redirected ->
                    When("Claude 요약 도중 워커가 취소되면 (리다이렉트=$redirected)") {
                        lateinit var job: AiJob
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            job = enqueue()
                            http.holdResponse = true
                            http.holdBody = true
                            if (redirected) {
                                http.messagesRedirect = "/redirected/messages"
                                http.heldPath = "/redirected/messages"
                            }
                        }
                        Then("공급자 호출을 취소하고 완료나 실패를 저장하지 않는다") {
                            coroutineScope {
                                val worker = launch { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                                try {
                                    val held = withTimeout(5000) { http.heldRequests.receive() }
                                    held.method shouldBe "POST"
                                    held.url.rawPath shouldBe
                                        if (redirected) {
                                            "/redirected/messages"
                                        } else {
                                            "/v1/messages"
                                        }
                                    withTimeout(3000) { worker.cancelAndJoin() }
                                } finally {
                                    http.releaseResponses()
                                }
                            }
                            http.requests.count { it.method == "POST" } shouldBe
                                if (redirected) {
                                    2
                                } else {
                                    1
                                }
                            jobs.findById(job.id!!)!!.let {
                                it.status shouldBe AiJobStatus.P
                                (it.startedAt != null) shouldBe true
                            }
                            links.findById(job.linkId)!!.status shouldBe LinkStatus.G
                            recordedUsage(job) shouldBe null
                            todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                        }
                    }
                }

                val ignoredBlocks: Map<String, (JsonArray) -> List<JsonElement>> =
                    linkedMapOf(
                        "가져오기 호출 ID 누락" to { blocks -> blocks.editBlock(1) { JsonObject(it - "id") } },
                        "숫자 가져오기 호출 ID" to { blocks -> blocks.editBlock(1) { JsonObject(it + ("id" to JsonPrimitive(3))) } },
                        "가져오기 결과의 호출 ID 누락" to { blocks -> blocks.editBlock(2) { JsonObject(it - "tool_use_id") } },
                        "숫자 가져오기 결과 ID" to { blocks ->
                            blocks.editBlock(2) { JsonObject(it + ("tool_use_id" to JsonPrimitive(3))) }
                        },
                        "중간 블록 종류 누락" to { blocks -> blocks.dropLast(1) + buildJsonObject {} + blocks.last() },
                        "숫자 중간 블록 종류" to { blocks ->
                            blocks.dropLast(1) + buildJsonObject { put("type", 13) } + blocks.last()
                        },
                        "숫자 이름의 두 번째 서버 도구" to { blocks ->
                            val invalidCall = JsonObject(blocks[1].jsonObject + ("name" to JsonPrimitive(13)))
                            blocks.dropLast(1) + invalidCall + blocks.last()
                        },
                    )
                val ignoredDocuments: Map<String, (JsonArray) -> List<JsonElement>> =
                    linkedMapOf(
                        "결과의 원문 문서 누락" to { blocks -> blocks.editFetchResult { JsonObject(it - "content") } },
                        "원문 문서의 source 누락" to { blocks -> blocks.editFetchDocument { JsonObject(it - "source") } },
                        "원문 문서에 잘못된 미디어 유형" to { blocks ->
                            blocks.editFetchDocument { document ->
                                val source = document.getValue("source").jsonObject + ("media_type" to JsonPrimitive("text/plain"))
                                JsonObject(document + ("source" to JsonObject(source)))
                            }
                        },
                        "원문 문서의 빈 데이터" to { blocks ->
                            blocks.editFetchDocument { document ->
                                val source = document.getValue("source").jsonObject + ("data" to JsonPrimitive(""))
                                JsonObject(document + ("source" to JsonObject(source)))
                            }
                        },
                    )
                val messagesWithIgnoredBlocks: Map<String, (AiJob) -> String> =
                    linkedMapOf(
                        "가져오기 요청의 URL 불일치" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), fetchUrl = otherUrl)
                        },
                        "가져온 결과의 URL 불일치" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), resultUrl = otherUrl)
                        },
                        "다른 도구 호출 ID" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), resultId = "wrong")
                        },
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
                        "가져오기 결과 누락" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), fetchStatus = null)
                        },
                        "다른 URL의 두 번째 가져오기" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    val secondCall =
                                        buildJsonObject {
                                            put("type", "server_tool_use")
                                            put("name", "web_fetch")
                                            put("id", "srvtoolu_second")
                                            put("input", buildJsonObject { put("url", otherUrl) })
                                        }
                                    val result = blocks[2].jsonObject
                                    val secondResult = JsonObject(result + ("tool_use_id" to JsonPrimitive("srvtoolu_second")))
                                    blocks.dropLast(1) + secondCall + secondResult + blocks.last()
                                },
                            )
                        },
                        "성공 뒤 max_uses_exceeded 결과" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    val extraResult =
                                        JsonObject(
                                            blocks[2].jsonObject + (
                                                "content" to
                                                    buildJsonObject {
                                                        put("type", "web_fetch_tool_result_error")
                                                        put("error_code", "max_uses_exceeded")
                                                    }
                                            ),
                                        )
                                    blocks.dropLast(1) + extraResult + blocks.last()
                                },
                            )
                        },
                        "호출 앞의 중복 결과" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> listOf(blocks[0], blocks[2], blocks[1], blocks[2], blocks.last()) },
                            )
                        },
                        "호출 앞에만 있는 결과" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> listOf(blocks[0], blocks[2], blocks[1], blocks.last()) },
                            )
                        },
                        "최종 블록의 알 수 없는 종류" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> blocks + unknownClaudeBlock },
                            )
                        },
                        "요약 텍스트만 있는 응답" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> listOf(blocks.last()) },
                            )
                        },
                        "최종 텍스트 뒤의 도구와 thinking 블록" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    val thinking =
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", RandomFixture.randomSentenceWithMax(30))
                                            put("signature", RandomFixture.randomSentenceWithMax(30))
                                        }
                                    blocks + blocks[1] + blocks[2] + thinking
                                },
                            )
                        },
                        "서로 다른 링크 ID의 요약 텍스트 두 개" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> listOf(blocks[0], blocks.last()) },
                            )
                        },
                    )
                val ignoredWireShapes =
                    (ignoredBlocks + ignoredDocuments).mapValues { (_, edit) ->
                        { job: AiJob -> fixture.messagesResponse(finalText = fixture.summaryJson(job.linkId, null), editContent = edit) }
                    }
                (messagesWithIgnoredBlocks + ignoredWireShapes).forEach { (scenario, body) ->
                    When("Claude 응답이 '$scenario'이면") {
                        lateinit var job: AiJob
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            job = enqueue()
                            http.messagesBody = body(job)
                        }
                        val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                        Then("마지막 요약 텍스트와 액션·사용량을 저장한다") {
                            shouldNotThrow<BusinessException> { action() }
                            appender.list.any { it.formattedMessage.startsWith("UPDATE ai_jobs") } shouldBe true
                            jobs.findById(job.id!!)!!.let {
                                it.status shouldBe AiJobStatus.C
                                it.response shouldBe fixture.summaryJson(job.linkId, null)
                                it.responseModelId shouldBe model.id
                            }
                            links.findById(job.linkId)!!.let {
                                it.status shouldBe LinkStatus.A
                                it.title shouldBe fixture.title
                                it.summary shouldBe fixture.summary
                                it.tags shouldBe listOf(fixture.tag)
                            }
                            todos.findAllByLinkId(job.linkId).map { it.title }.toSet() shouldBe
                                setOf(fixture.scheduledAction, fixture.unscheduledAction)
                            recordedUsage(job)!!.let {
                                it.requests shouldBe 1
                                it.tokens shouldBe 26
                            }
                        }
                    }
                }

                val invalidMessages: Map<String, (AiJob) -> String> =
                    linkedMapOf(
                        "정상 텍스트 뒤 최종 텍스트 필드 누락" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> blocks + buildJsonObject { put("type", "text") } },
                            )
                        },
                        "정상 텍스트 뒤 최종 텍스트 필드 타입 오류" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    blocks +
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", JsonArray(emptyList()))
                                        }
                                },
                            )
                        },
                        "정상 텍스트 뒤 빈 최종 텍스트" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    blocks +
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", " ")
                                        }
                                },
                            )
                        },
                        "도구 블록만 있는 응답" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> blocks.drop(1).dropLast(1) },
                            )
                        },
                        "내용 블록이 빈 응답" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { emptyList() },
                            )
                        },
                        "내용 블록 배열의 null 항목" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> blocks + JsonNull },
                            )
                        },
                        "내용 블록 배열의 숫자 항목" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> listOf(JsonPrimitive(RandomFixture.randomId())) + blocks },
                            )
                        },
                        "내용 블록 배열의 배열 항목" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks -> blocks.dropLast(1) + listOf(JsonArray(emptyList()), blocks.last()) },
                            )
                        },
                        "최종 텍스트의 잘못된 필드 형식" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editContent = { blocks ->
                                    blocks.dropLast(1) +
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", JsonArray(emptyList()))
                                        }
                                },
                            )
                        },
                        "빈 최종 텍스트" to { fixture.messagesResponse(finalText = " ") },
                        "refusal" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "refusal")
                        },
                        "최종 텍스트와 사용량이 잘못된 refusal" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                stopReason = "refusal",
                                editContent = { blocks -> blocks + buildJsonObject { put("type", "text") } },
                                editUsage = { JsonNull },
                            )
                        },
                        "max_tokens" to
                            { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "max_tokens") },
                        "pause_turn" to
                            { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "pause_turn") },
                        "tool_use 종료" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "tool_use")
                        },
                        "깨진 JSON" to { fixture.messagesResponse(finalText = "{${ClaudeSummaryFixture.PAGE_MARKER}") },
                        "깨진 SDK 응답" to { "{${ClaudeSummaryFixture.PAGE_MARKER}" },
                        "잘못된 토큰 사용량" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), inputTokens = Int.MAX_VALUE.toLong())
                        },
                        "문자열 토큰" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editUsage = { usage -> JsonObject(usage + ("input_tokens" to JsonPrimitive("11"))) },
                            )
                        },
                        "소수 토큰" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editUsage = { usage -> JsonObject(usage + ("output_tokens" to JsonPrimitive(3.5))) },
                            )
                        },
                        "음수 캐시 토큰" to {
                            fixture.messagesResponse(
                                finalText = fixture.summaryJson(it.linkId, null),
                                editUsage = { usage -> JsonObject(usage + ("cache_read_input_tokens" to JsonPrimitive(-1))) },
                            )
                        },
                        "null 사용량" to {
                            fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), editUsage = { JsonNull })
                        },
                        "깨진 최종 JSON과 누락된 사용량" to {
                            fixture.messagesResponse(
                                finalText = "{${ClaudeSummaryFixture.PAGE_MARKER}",
                                editUsage = { JsonNull },
                            )
                        },
                        "링크 ID 누락" to { fixture.messagesResponse(finalText = fixture.summaryJson(null, null)) },
                        "링크 ID 불일치" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId + 1, null)) },
                    )
                invalidMessages.forEach { (scenario, body) ->
                    When("Claude 응답이 '$scenario'이면") {
                        lateinit var job: AiJob
                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            job = enqueue()
                            http.messagesBody = body(job)
                        }
                        val action = suspend { app.koin.get<AiSummaryWorker>().proceed(job.id!!) }
                        Then("실패 사유만 저장하고 요약·액션·사용량을 반영하지 않는다") {
                            shouldNotThrow<BusinessException> { action() }
                            appender.list.any { it.formattedMessage.startsWith("UPDATE ai_jobs") } shouldBe true
                            val error =
                                when (scenario) {
                                    "빈 최종 텍스트",
                                    "정상 텍스트 뒤 빈 최종 텍스트",
                                    "도구 블록만 있는 응답",
                                    "내용 블록이 빈 응답",
                                    -> {
                                        ErrorCode.AI_EMPTY_RESPONSE
                                    }

                                    "refusal", "최종 텍스트와 사용량이 잘못된 refusal" -> {
                                        ErrorCode.AI_SUMMARY_REFUSED
                                    }

                                    "max_tokens" -> {
                                        ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED
                                    }

                                    "pause_turn", "tool_use 종료" -> {
                                        ErrorCode.AI_SUMMARY_INCOMPLETE
                                    }

                                    "깨진 JSON",
                                    "깨진 SDK 응답",
                                    "정상 텍스트 뒤 최종 텍스트 필드 누락",
                                    "정상 텍스트 뒤 최종 텍스트 필드 타입 오류",
                                    "최종 텍스트의 잘못된 필드 형식",
                                    "내용 블록 배열의 null 항목",
                                    "내용 블록 배열의 숫자 항목",
                                    "내용 블록 배열의 배열 항목",
                                    -> {
                                        ErrorCode.AI_SUMMARY_RESPONSE_INVALID
                                    }

                                    "잘못된 토큰 사용량",
                                    "문자열 토큰",
                                    "소수 토큰",
                                    "음수 캐시 토큰",
                                    "null 사용량",
                                    "깨진 최종 JSON과 누락된 사용량",
                                    -> {
                                        ErrorCode.AI_SUMMARY_USAGE_INVALID
                                    }

                                    else -> {
                                        ErrorCode.AI_SUMMARY_LINK_MISMATCH
                                    }
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
        }
    })

private val unknownClaudeBlock =
    buildJsonObject {
        put("type", "future_block")
        put("content", JsonArray(emptyList()))
    }

private fun JsonArray.editBlock(
    index: Int,
    edit: (JsonObject) -> JsonObject,
): List<JsonElement> =
    mapIndexed { position, block ->
        if (position == index) {
            edit(block.jsonObject)
        } else {
            block
        }
    }

private fun JsonArray.editFetchResult(edit: (JsonObject) -> JsonObject): List<JsonElement> =
    editBlock(2) { block ->
        JsonObject(block + ("content" to edit(block.getValue("content").jsonObject)))
    }

private fun JsonArray.editFetchDocument(edit: (JsonObject) -> JsonObject): List<JsonElement> =
    editFetchResult { result ->
        JsonObject(result + ("content" to edit(result.getValue("content").jsonObject)))
    }

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
    tool.getValue("type").jsonPrimitive.content shouldBe "web_fetch_20260318"
    tool.getValue("name").jsonPrimitive.content shouldBe "web_fetch"
    tool.getValue("max_uses").jsonPrimitive.int shouldBe 1
    tool
        .getValue("allowed_callers")
        .jsonArray
        .single()
        .jsonPrimitive.content shouldBe "direct"
    tool.containsKey("response_inclusion") shouldBe false
    tool.containsKey("use_cache") shouldBe false
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
