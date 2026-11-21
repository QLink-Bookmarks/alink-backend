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
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.service.GetAiProviderModelsService
import com.qlink.ai.service.PutAiUserProviderService
import com.qlink.ai.service.UpdateAiProviderActiveService
import com.qlink.ai.service.UpdateAvailableModelActiveService
import com.qlink.ai.service.UpdateLinkAiSummaryService
import com.qlink.ai.worker.AiSummaryWorker
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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset

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
        val config = ServiceTestEnvironment.applicationConfig()
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
            val response =
                app.koin.get<UpdateLinkAiSummaryService>().updateLinkAiSummary(
                    loginId = owner.id!!,
                    request =
                        AiSummaryRequest(
                            userProviderId = registeredId,
                            modelId = selected.id,
                            url = fixture.sourceUrl,
                            generateTodo = true,
                        ),
                )
            return jobs.findAllByLinkId(response.id).single()
        }

        suspend fun recordedUsage(job: AiJob) =
            usage.findByUserIdAndProviderIdAndUsageDate(
                userProviderId = job.userProviderId,
                modelId = job.requestModelId,
                usageDate = LocalDate.now(ZoneOffset.UTC),
            )

        fun assertPrivateValuesAbsent(text: String) {
            listOf(
                apiKey,
                fixture.sourceUrl,
                "claude-source.example",
                ClaudeSummaryFixture.PAGE_MARKER,
                ClaudeSummaryFixture.PDF_MARKER,
                ClaudeHttpFixture.TRANSPORT_MARKER,
            ).forEach { text shouldNotContain it }
        }

        Given("운영 라우터에 등록된 Claude와 HTTP 응답 fixture") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                http = ClaudeHttpFixture()
                app =
                    koinApplication {
                        modules(
                            externalModule(config = config, s3Config = s3Config),
                            repositoryModule(),
                            serviceModule(),
                            workerModule(config),
                            module {
                                single(named<ClaudeAiClient>()) { http.client } onClose { it?.close() }
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
                    ClaudeSummaryFixture(
                        summary = "${ClaudeSummaryFixture.PAGE_MARKER} ${RandomFixture.randomSentenceWithMax(100)}",
                    )
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                owner = users.insert(UserFixture.createRandomValidUser())
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false))
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                folder = folders.insert(FolderFixture.createFolderWith(ownerId = owner.id!!, name = otherUrl))
            }

            afterTest { (testCase, _) ->
                if (testCase.type != TestType.Test) return@afterTest
                loggers.forEach { logger -> logger.detachAppender(appender) }
                val logs =
                    appender.list.joinToString("\n") { event ->
                        event.formattedMessage + event.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty()
                    }
                appender.stop()
                http.client.close()
                app.close()
                assertPrivateValuesAbsent(logs)
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
                HttpStatusCode.Forbidden to ErrorCode.AI_API_KEY_INVALID,
                HttpStatusCode.ServiceUnavailable to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                HttpStatusCode.Found to ErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                HttpStatusCode.TooManyRequests to ErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                null to ErrorCode.COMMON_INTERNAL_SERVER_ERROR,
            ).forEach { (status, error) ->
                When("키 검증 결과가 ${status?.value ?: "전송 실패"}이면") {
                    var existingId: Long? = null
                    beforeTest {
                        if (it.type != TestType.Test) return@beforeTest
                        activateProvider()
                        existingId = if (error == ErrorCode.AI_API_KEY_INVALID) register() else null
                        http.requests.clear()
                        http.modelsStatus = status
                    }
                    val action = suspend { register("dummy-rejected-key-${RandomFixture.randomId()}") }
                    Then("기존 API 오류로 매핑하고 새 키를 저장하지 않는다") {
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
                When("공급자 기본 주소 경로가 '$path'이고 두 호출이 리다이렉트되면") {
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
                        http.modelsStatus = HttpStatusCode.Found
                        http.messagesStatus = HttpStatusCode.Found
                    }
                    val checkKey = suspend { register() }
                    val summarize =
                        suspend {
                            val router = app.koin.get<AiClientRouter>()
                            router.summarize(
                                AiSummaryClientRequest(
                                    providerType = AiProviderType.CLAUDE,
                                    baseUrl = provider.baseUrl,
                                    apiKey = apiKey,
                                    model = model.model,
                                    prompt = RandomFixture.randomSentenceWithMax(100),
                                    requestedUrl = fixture.sourceUrl,
                                ),
                            )
                        }
                    Then("공식 엔드포인트에만 키와 버전 헤더를 전송한다") {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.message) { checkKey() }
                        shouldThrowWithMessage<BusinessException>(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE.message) { summarize() }
                        http.requests.map { it.method to it.url.toString() } shouldBe
                            listOf(HttpMethod.Get to "$baseUrl/v1/models?limit=1", HttpMethod.Post to "$baseUrl/v1/messages")
                        http.requests.forEach { request ->
                            request.headers["x-api-key"] shouldBe apiKey
                            request.headers["anthropic-version"] shouldBe "2023-06-01"
                        }
                    }
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

                        appender.list.any { it.formattedMessage == "SQL operation=INSERT values redacted" } shouldBe true
                        verifyMessagesRequest(request = http.requests.last(), job = job, model = model.model, sourceUrl = fixture.sourceUrl)
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
                    "가져오기 결과 누락" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), fetchStatus = null) },
                    "빈 최종 텍스트" to { fixture.messagesResponse(finalText = " ") },
                    "refusal" to { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "refusal") },
                    "max_tokens" to
                        { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "max_tokens") },
                    "pause_turn" to
                        { fixture.messagesResponse(finalText = fixture.summaryJson(it.linkId, null), stopReason = "pause_turn") },
                    "깨진 JSON" to { fixture.messagesResponse(finalText = "{${ClaudeSummaryFixture.PAGE_MARKER}") },
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
                        appender.list.any { it.formattedMessage == "SQL operation=UPDATE values redacted" } shouldBe true
                        val error = if (scenario.startsWith("링크 ID")) ErrorCode.AI_SUMMARY_LINK_MISMATCH else ErrorCode.AI_EMPTY_RESPONSE
                        jobs.findById(job.id!!)!!.let {
                            it.status shouldBe AiJobStatus.F
                            it.response shouldBe null
                            it.responseModelId shouldBe null
                        }
                        links.findById(job.linkId)!!.let {
                            it.status shouldBe LinkStatus.F
                            it.summary shouldBe error.message
                            assertPrivateValuesAbsent(it.summary!!)
                        }
                        recordedUsage(job) shouldBe null
                        todos.findAllByLinkId(job.linkId) shouldBe emptyList()
                    }
                }
            }
        }
    })

private fun verifyMessagesRequest(
    request: HttpRequestData,
    job: AiJob,
    model: String,
    sourceUrl: String,
) {
    val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
    body.getValue("system").jsonPrimitive.content shouldBe AiSummarySpec.systemInstruction
    body.getValue("model").jsonPrimitive.content shouldBe model
    body.getValue("max_tokens").jsonPrimitive.int shouldBe 4096
    val userMessage =
        body
            .getValue("messages")
            .jsonArray
            .single()
            .jsonObject
    userMessage.getValue("role").jsonPrimitive.content shouldBe "user"
    userMessage.getValue("content").jsonPrimitive.content shouldContain sourceUrl
    userMessage.getValue("content").jsonPrimitive.content shouldContain job.prompt

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
