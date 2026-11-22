package com.qlink.ai.service

import com.google.genai.Client
import com.openai.client.OpenAIClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.client.GeminiCurlClient
import com.qlink.ai.client.GeminiSdkClient
import com.qlink.ai.client.OpenAiCurlClient
import com.qlink.ai.client.OpenAiSdkClient
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.worker.AiSummaryWorker
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.AiSdkHttpTransport
import com.qlink.di.externalModule
import com.qlink.di.pluginModule
import com.qlink.di.repositoryModule
import com.qlink.di.serviceModule
import com.qlink.di.transactionModule
import com.qlink.di.workerModule
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.AiProviderServer
import com.qlink.support.fixture.LinkFixture
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
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiSdkServiceTest :
    BaseServiceTest({
        val database = koinGet<Database>()
        val userRepository = koinGet<UserRepository>()
        val providerRepository = koinGet<AiProviderRepository>()
        val userProviderRepository = koinGet<UserProviderRepository>()
        val modelRepository = koinGet<AvailableModelRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val jobRepository = koinGet<AiJobRepository>()
        val usageRepository = koinGet<DailyUsageRepository>()
        val todoRepository = koinGet<TodoRepository>()

        fun providerApplication(
            overrides: MapApplicationConfig,
            vararg overridesModules: Module,
        ): KoinApplication {
            val config = ServiceTestEnvironment.applicationConfig().mergeWith(overrides)
            return koinApplication {
                modules(
                    module { single { database } },
                    transactionModule(),
                    repositoryModule(),
                    pluginModule(config),
                    externalModule(config = config, s3Config = ServiceTestEnvironment.s3Config(environment = "dev")),
                    serviceModule(),
                    workerModule(config),
                    *overridesModules,
                )
            }
        }

        Given("OpenAI와 Gemini SDK를 모두 끈 운영 설정") {
            When("두 공급자의 라우터를 불러오면") {
                Then("SDK와 공용 OkHttp 전송을 생성하지 않고 두 Ktor 어댑터를 선택한다") {
                    val app =
                        providerApplication(
                            MapApplicationConfig("ai.openai.useSdk" to "false", "ai.gemini.useSdk" to "false"),
                            module {
                                single<OpenAIClient> { error("Unexpected OpenAI SDK creation") }
                                single<Client> { error("Unexpected Gemini SDK creation") }
                                single<AiSdkHttpTransport> { error("Unexpected SDK transport creation") }
                            },
                        )
                    try {
                        val router = koinGet<AiClientRouter>(app.koin)
                        router.requireSupported(AiProviderType.OPENAI)::class shouldBe OpenAiCurlClient::class
                        router.requireSupported(AiProviderType.GEMINI)::class shouldBe GeminiCurlClient::class
                    } finally {
                        app.close()
                    }
                }
            }
        }

        Given("잘못된 AI SDK 전환 설정") {
            val settings =
                mapOf(
                    "ai.openai.useSdk" to ErrorCode.CONFIG_OPENAI_USE_SDK_INVALID,
                    "ai.gemini.useSdk" to ErrorCode.CONFIG_GEMINI_USE_SDK_INVALID,
                )
            for ((path, errorCode) in settings) {
                When("$path 값이 비어 있거나 불리언이 아니면") {
                    val start = { value: String -> providerApplication(MapApplicationConfig(path to value)).close() }
                    Then("해당 공급자의 설정 키를 포함해 구성을 거부한다") {
                        for (value in listOf("", RandomFixture.randomId().toString())) {
                            shouldThrowWithMessage<BusinessException>(errorCode.message) { start(value) }
                        }
                    }
                }
            }
        }

        for ((providerType, basePath) in listOf(
            AiProviderType.OPENAI to "/v1",
            AiProviderType.GEMINI to "/v1beta",
            AiProviderType.GEMINI to "",
            AiProviderType.OPENAI to "/gateway//v1",
            AiProviderType.GEMINI to "/gateway//v1",
        )) {
            Given("$providerType ${basePath.ifEmpty { "/" }} 운영 공급자에 키를 등록하고 요약을 요청하는 사용자") {
                val configPrefix =
                    if (providerType == AiProviderType.OPENAI) {
                        "ai.openai"
                    } else {
                        "ai.gemini"
                    }
                lateinit var application: KoinApplication
                lateinit var server: AiProviderServer
                lateinit var provider: AiProvider
                lateinit var user: User

                beforeTest {
                    if (it.type != TestType.Test) {
                        return@beforeTest
                    }
                    server = AiProviderServer()
                    application =
                        providerApplication(
                            MapApplicationConfig("$configPrefix.sdkEndpoint" to (server.baseUrl + basePath)),
                        )
                    provider =
                        providerRepository.insert(
                            AiProvider(
                                type = providerType,
                                baseUrl = server.baseUrl + "/stored" + basePath,
                                activeAt = RandomFixture.randomPastInstant(),
                            ),
                        )
                    user = userRepository.insert(UserFixture.createRandomValidUser())
                }

                afterTest {
                    if (it.a.type == TestType.Test) {
                        application.close()
                        server.close()
                    }
                }

                fun registration(apiKey: String) = PutAiUserProviderRequest(providerId = provider.id!!, apiKey = apiKey)

                suspend fun register(apiKey: String) =
                    koinGet<PutAiUserProviderService>(
                        application.koin,
                    ).putAiUserProvider(loginId = user.id!!, request = registration(apiKey))

                fun summaryRequest(apiKey: String) =
                    AiSummaryClientRequest(
                        providerType = providerType,
                        baseUrl = provider.baseUrl,
                        apiKey = apiKey,
                        model = "model-${RandomFixture.randomId()}",
                        prompt = RandomFixture.randomSentenceWithMax(100),
                    )

                When("모델 목록 GET이 빈 본문 또는 다음 페이지 토큰을 포함하거나 해석할 수 없는 2xx를 반환하면") {
                    Then("키를 등록 및 갱신하고 등록마다 목록을 한 번만 호출한다") {
                        val cipher = koinGet<ApiKeyCipher>(application.koin)
                        val priorIds = mutableSetOf<Long>()
                        for (body in listOf(
                            "",
                            """{"data":[],"models":[]}""",
                            """{"has_more":true,"nextPageToken":"next"}""",
                            "not-json",
                        )) {
                            server.reply = { AiProviderServer.Reply(body = body) }
                            val apiKey = "test-${RandomFixture.randomId()}"
                            priorIds.add(register(apiKey).id)

                            val saved = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                            saved.apiKey shouldNotBe apiKey
                            cipher.decrypt(saved.apiKey) shouldBe apiKey
                            assertAuthentication(request = server.requests.last(), providerType = providerType, key = apiKey)
                        }
                        server.requests
                            .map { it.remotePort }
                            .distinct()
                            .size shouldBe 1
                        priorIds.size shouldBe 1
                        server.requests.map { it.method to it.path } shouldBe List(4) { "GET" to "$basePath/models" }
                    }
                }

                When("공급자가 등록을 거부하면") {
                    Then("본래 HTTP 상태를 오류 코드로 변환하고 기존 암호문을 유지한다") {
                        AiProviderServer().use { destination ->
                            val previousKey = "test-${RandomFixture.randomId()}"
                            register(previousKey)
                            val previous = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                            val errors =
                                listOf(
                                    Triple(401, ErrorCode.AI_API_KEY_INVALID, false),
                                    Triple(403, ErrorCode.AI_VENDOR_PERMISSION_DENIED, false),
                                    Triple(400, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                    Triple(404, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                    Triple(413, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                    Triple(418, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                    Triple(422, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                    Triple(503, ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE, false),
                                    Triple(429, ErrorCode.AI_VENDOR_RATE_LIMITED, false),
                                    Triple(429, ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED, true),
                                    Triple(307, ErrorCode.AI_VENDOR_REQUEST_REJECTED, false),
                                )
                            for ((status, code, quotaExhausted) in errors) {
                                val rejectedKey = "test-${RandomFixture.randomId()}"
                                server.reply = {
                                    AiProviderServer.Reply(
                                        status = status,
                                        body =
                                            if (quotaExhausted) {
                                                quotaError(providerType = providerType, key = rejectedKey)
                                            } else {
                                                "malformed provider response echoed $rejectedKey"
                                            },
                                        location = destination.baseUrl.takeIf { status == 307 },
                                        retryAfter = "0".takeIf { status == 503 },
                                    )
                                }
                                val replace = suspend { register(rejectedKey) }

                                val error = shouldThrowWithMessage<BusinessException>(code.message) { replace() }
                                error.errorCode shouldBe code
                                error.cause shouldBe null
                                val saved = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                                saved.id shouldBe previous.id
                                saved.apiKey shouldBe previous.apiKey
                            }
                            server.requests.map { it.method to it.path } shouldBe List(errors.size + 1) { "GET" to "$basePath/models" }
                            server.reply = {
                                AiProviderServer.Reply(status = 307, body = "{}", location = destination.baseUrl)
                            }
                            val router = koinGet<AiClientRouter>(application.koin)
                            val failure =
                                runCatching {
                                    router.summarize(
                                        summaryRequest(previousKey),
                                    )
                                }.exceptionOrNull() as BusinessException
                            failure.errorCode shouldBe ErrorCode.AI_VENDOR_REQUEST_REJECTED
                            server.requests.count { it.method == "POST" } shouldBe 1
                            destination.requests shouldBe emptyList()
                        }
                    }
                }

                val errorResponses =
                    listOf(
                        401 to true,
                        403 to true,
                        400 to true,
                        404 to true,
                        413 to true,
                        422 to true,
                        429 to true,
                        429 to false,
                        503 to true,
                        503 to false,
                        200 to false,
                        200 to true,
                    )
                for ((status, validJson) in errorResponses) {
                    val retryAfter = "3600".takeIf { status == 429 && !validJson }
                    val expectedCode =
                        when (status) {
                            200 -> ErrorCode.AI_SUMMARY_RESPONSE_INVALID
                            401 -> ErrorCode.AI_API_KEY_INVALID
                            403 -> ErrorCode.AI_VENDOR_PERMISSION_DENIED
                            429 -> ErrorCode.AI_VENDOR_RATE_LIMITED
                            503 -> ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE
                            else -> ErrorCode.AI_VENDOR_REQUEST_REJECTED
                        }
                    val responseType =
                        when {
                            !validJson -> "깨진 본문"
                            status == 200 -> "깨진 모델 출력"
                            else -> "오류 JSON"
                        }
                    When("요약 후보가 HTTP $status 응답과 ${responseType}을 보내면") {
                        lateinit var key: UserProvider
                        lateinit var model: AvailableModel
                        lateinit var link: Link
                        val apiKey = "test-${RandomFixture.randomId()}"

                        beforeTest {
                            if (it.type != TestType.Test) {
                                return@beforeTest
                            }
                            key =
                                userProviderRepository.insert(
                                    UserProvider(
                                        userId = user.id!!,
                                        providerId = provider.id!!,
                                        apiKey = koinGet<ApiKeyCipher>(application.koin).encrypt(apiKey),
                                    ),
                                )
                            model = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 1))
                            modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 2))
                            link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                        }

                        Then("요약 작업과 링크에 응답 본문에 해당하는 실패가 저장된다") {
                            val body =
                                when {
                                    !validJson -> {
                                        "not-json: provider echoed $apiKey"
                                    }

                                    status == 200 -> {
                                        summaryEnvelope(providerType = providerType, text = "not-json: model echoed $apiKey", tokens = 0)
                                    }

                                    else -> {
                                        """{"error":{"message":"provider echoed $apiKey"}}"""
                                    }
                                }
                            server.reply = {
                                AiProviderServer.Reply(
                                    status = status,
                                    body = body,
                                    retryAfter = retryAfter ?: "0".takeIf { status == 503 },
                                )
                            }
                            val request =
                                AiSummaryRequest(id = link.id!!, userProviderId = key.id!!, modelId = model.id!!, url = link.url)
                            koinGet<UpdateLinkAiSummaryService>(
                                application.koin,
                            ).updateLinkAiSummary(loginId = user.id!!, request = request)
                            val jobId = withTimeout(2_000) { koinGet<Channel<Long>>(application.koin).receive() }

                            val proceed = suspend { koinGet<AiSummaryWorker>(application.koin).proceed(jobId) }
                            withTimeout(5_000) { shouldNotThrow<BusinessException> { proceed() } }

                            val job = jobRepository.findById(jobId)!!
                            val saved = linkRepository.findById(link.id!!)!!
                            job.status shouldBe AiJobStatus.F
                            job.response shouldBe null
                            saved.status shouldBe LinkStatus.F
                            saved.summary shouldBe expectedCode.message
                            val captured = server.requests.single()
                            captured.method shouldBe "POST"
                            assertAuthentication(request = captured, providerType = providerType, key = apiKey)
                        }
                    }
                }

                When("우선순위 모델의 요약 요청이 실패한 후 다음 모델이 성공하면") {
                    lateinit var key: UserProvider
                    lateinit var primary: AvailableModel
                    lateinit var fallback: AvailableModel
                    lateinit var link: Link
                    val apiKey = "test-${RandomFixture.randomId()}"

                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        key =
                            userProviderRepository.insert(
                                UserProvider(
                                    userId = user.id!!,
                                    providerId = provider.id!!,
                                    userRole = Role.SUPER_ADMIN,
                                    apiKey = koinGet<ApiKeyCipher>(application.koin).encrypt(apiKey),
                                ),
                            )
                        primary = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 1))
                        fallback = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 2))
                        link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                    }

                    Then("후보별 POST가 한 번씩 전송되고 성공 모델과 요약 결과가 저장된다") {
                        val request =
                            AiSummaryRequest(id = link.id!!, userProviderId = key.id!!, modelId = primary.id!!, url = link.url)
                        koinGet<UpdateLinkAiSummaryService>(application.koin).updateLinkAiSummary(loginId = user.id!!, request = request)
                        val jobId = withTimeout(2_000) { koinGet<Channel<Long>>(application.koin).receive() }
                        val prompt = jobRepository.findById(jobId)!!.prompt
                        val title = RandomFixture.randomSentenceWithMax(30)
                        val summary = RandomFixture.randomSentenceWithMax(100)
                        val tag = RandomFixture.randomSentenceWithMax(8)
                        val todoTitle = RandomFixture.randomSentenceWithMax(30)
                        val tokens = RandomFixture.randomInt(1, 50)
                        val output =
                            buildJsonObject {
                                put("id", RandomFixture.randomId())
                                put("linkId", link.id!!)
                                put("folderId", JsonNull)
                                put("title", title)
                                put("summary", summary)
                                put("tags", buildJsonArray { add(JsonPrimitive(tag)) })
                                put(
                                    "todos",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("title", todoTitle)
                                                put("reminderAt", JsonNull)
                                            },
                                        )
                                    },
                                )
                            }
                        val text = "  ```json\n$output\n```  "
                        val success = summaryEnvelope(providerType = providerType, text = text, tokens = tokens)
                        server.reply = {
                            if (requestModel(request = it, providerType = providerType) == primary.model) {
                                AiProviderServer.Reply(status = 503, body = """{"error":{"message":"unavailable"}}""", retryAfter = "0")
                            } else {
                                AiProviderServer.Reply(body = success)
                            }
                        }

                        val proceed = suspend { koinGet<AiSummaryWorker>(application.koin).proceed(jobId) }
                        shouldNotThrow<BusinessException> { proceed() }

                        server.requests.map { requestModel(request = it, providerType = providerType) } shouldBe
                            listOf(primary.model, fallback.model)
                        for (captured in server.requests) {
                            assertAuthentication(request = captured, providerType = providerType, key = apiKey)
                            assertSummaryRequest(request = captured, providerType = providerType, basePath = basePath, prompt = prompt)
                        }
                        val job = jobRepository.findById(jobId)!!
                        job.status shouldBe AiJobStatus.C
                        job.responseModelId shouldBe fallback.id
                        job.response shouldBe output.toString()
                        val savedLink = linkRepository.findById(link.id!!)!!
                        savedLink.title shouldBe title
                        savedLink.summary shouldBe summary
                        savedLink.tags shouldBe listOf(tag)
                        savedLink.folderId shouldBe null
                        todoRepository.findAllByLinkId(link.id!!).single().title shouldBe todoTitle
                        val usage =
                            usageRepository.findByUserIdAndProviderIdAndUsageDate(
                                userProviderId = key.id!!,
                                modelId = fallback.id!!,
                                usageDate = LocalDate.now(ZoneOffset.UTC),
                            )!!
                        usage.tokens shouldBe tokens
                        usage.requests shouldBe 1
                    }
                }

                When("완료된 링크의 주소와 요약 모델을 교체하면") {
                    lateinit var link: Link
                    lateinit var first: AvailableModel
                    lateinit var replacement: AvailableModel
                    lateinit var key: UserProvider

                    beforeTest {
                        if (it.type != TestType.Test) {
                            return@beforeTest
                        }
                        val registered = register("test-${RandomFixture.randomId()}")
                        key = userProviderRepository.findById(registered.id)!!
                        first = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 1))
                        replacement =
                            modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 2))
                        link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                    }

                    Then("같은 링크에 새 모델의 결과를 저장하고 사용량을 각 모델에 기록한다") {
                        val service = koinGet<UpdateLinkAiSummaryService>(application.koin)
                        val worker = koinGet<AiSummaryWorker>(application.koin)
                        val channel = koinGet<Channel<Long>>(application.koin)
                        val replacementUrl = "https://example.com/${RandomFixture.randomId()}"
                        val tokens = RandomFixture.randomInt(1, 50)
                        for ((model, url) in listOf(first to link.url, replacement to replacementUrl)) {
                            val title = RandomFixture.randomSentenceWithMax(30)
                            val summary = RandomFixture.randomSentenceWithMax(100)
                            val output =
                                buildJsonObject {
                                    put("linkId", link.id!!)
                                    put("title", title)
                                    put("summary", summary)
                                }.toString()
                            server.reply =
                                {
                                    AiProviderServer.Reply(
                                        body = summaryEnvelope(providerType = providerType, text = output, tokens = tokens),
                                    )
                                }
                            service.updateLinkAiSummary(
                                loginId = user.id!!,
                                request = AiSummaryRequest(id = link.id!!, userProviderId = key.id!!, modelId = model.id!!, url = url),
                            )
                            val jobId = withTimeout(2_000) { channel.receive() }
                            val proceed = suspend { worker.proceed(jobId) }
                            shouldNotThrow<BusinessException> { proceed() }
                            val savedJob = jobRepository.findById(jobId)!!
                            savedJob.status shouldBe AiJobStatus.C
                            savedJob.responseModelId shouldBe model.id
                            val savedLink = linkRepository.findById(link.id!!)!!
                            savedLink.url shouldBe url
                            savedLink.title shouldBe title
                            savedLink.summary shouldBe summary
                            savedLink.workModelId shouldBe model.id
                            savedLink.status shouldBe LinkStatus.A
                            val usage =
                                usageRepository.findByUserIdAndProviderIdAndUsageDate(
                                    userProviderId = key.id!!,
                                    modelId = model.id!!,
                                    usageDate = LocalDate.now(ZoneOffset.UTC),
                                )!!
                            usage.requests shouldBe 1
                            usage.tokens shouldBe tokens
                        }
                        server.requests.filter { it.method == "POST" }.map { requestModel(it, providerType) } shouldBe
                            listOf(first.model, replacement.model)
                    }
                }

                When("공급자가 SDK 응답 타입과 다르지만 기존 요약 계약에는 맞는 본문을 보내면") {
                    Then("요약 텍스트와 사용량 기본값을 기존 방식으로 읽는다") {
                        val title = RandomFixture.randomSentenceWithMax(30)
                        val summary = RandomFixture.randomSentenceWithMax(100)
                        val output =
                            buildJsonObject {
                                put("title", title)
                                put("summary", summary)
                            }.toString()
                        val text = JsonPrimitive(output)
                        val body =
                            if (providerType == AiProviderType.OPENAI) {
                                """{"output":[{"content":[{"text":$text}]}],"usage":{}}"""
                            } else {
                                val candidate = """{"content":{"parts":[{"text":$text},{"text":"later"}]},"tokenCount":"ignored"}"""
                                """{"candidates":[$candidate],"usageMetadata":{}}"""
                            }
                        server.reply = { AiProviderServer.Reply(body = body) }

                        val router = koinGet<AiClientRouter>(application.koin)
                        val result = router.summarize(summaryRequest("test-${RandomFixture.randomId()}"))

                        result.rawResponse shouldBe output
                        result.title shouldBe title
                        result.summary shouldBe summary
                        result.usedTokens shouldBe 0
                        server.requests.single().method shouldBe "POST"
                    }
                }

                When("공급자가 요약 사용량 일부를 생략하거나 유효한 요약을 보내지 않으면") {
                    Then("사용량 기본값을 유지하고 빈 요약과 형식 오류를 구분한다") {
                        val router = koinGet<AiClientRouter>(application.koin)
                        val request = summaryRequest("test-${RandomFixture.randomId()}")
                        val linkId = RandomFixture.randomId()
                        val title = RandomFixture.randomSentenceWithMax(30)
                        val summary = RandomFixture.randomSentenceWithMax(100)
                        val output =
                            buildJsonObject {
                                put("id", linkId)
                                put("title", title)
                                put("summary", summary)
                            }.toString()
                        val usageField =
                            if (providerType == AiProviderType.OPENAI) {
                                "usage"
                            } else {
                                "usageMetadata"
                            }
                        val envelope =
                            Json
                                .parseToJsonElement(
                                    summaryEnvelope(providerType = providerType, text = output, tokens = 0),
                                ).jsonObject
                        val withoutUsage = envelope - usageField
                        for ((fields, expected) in listOf(
                            withoutUsage to output.length,
                            withoutUsage + (usageField to JsonObject(emptyMap())) to 0,
                            envelope to 0,
                        )) {
                            server.reply = { AiProviderServer.Reply(body = JsonObject(fields).toString()) }
                            val result = router.summarize(request)
                            result.usedTokens shouldBe expected
                            result.linkId shouldBe linkId
                            result.rawResponse shouldBe output
                        }

                        for (body in listOf(
                            "{}",
                            summaryEnvelope(providerType = providerType, text = "  ", tokens = 0, laterText = null),
                        )) {
                            server.reply = { AiProviderServer.Reply(body = body) }
                            val failure = runCatching { router.summarize(request) }.exceptionOrNull() as BusinessException
                            failure.errorCode shouldBe ErrorCode.AI_EMPTY_RESPONSE
                        }

                        server.reply =
                            { AiProviderServer.Reply(body = summaryEnvelope(providerType = providerType, text = "{invalid", tokens = 0)) }
                        val failure = runCatching { router.summarize(request) }.exceptionOrNull() as BusinessException
                        failure.errorCode shouldBe ErrorCode.AI_SUMMARY_RESPONSE_INVALID
                    }
                }

                When("서로 다른 키로 요약과 검증을 동시에 호출하면") {
                    Then("설정된 주소에서 각 요청에 자기 키만 전달된다") {
                        val router = koinGet<AiClientRouter>(application.koin)
                        val apiKey = "test-${RandomFixture.randomId()}"
                        val otherKey = "test-${RandomFixture.randomId()}"
                        val title = RandomFixture.randomSentenceWithMax(30)
                        val output =
                            buildJsonObject {
                                put("title", title)
                                put("summary", title)
                            }.toString()
                        server.reply = {
                            if (it.method == "POST") {
                                AiProviderServer.Reply(body = summaryEnvelope(providerType = providerType, text = output, tokens = 0))
                            } else {
                                AiProviderServer.Reply()
                            }
                        }
                        val first = summaryRequest(apiKey)
                        val second = summaryRequest(otherKey)
                        coroutineScope {
                            val summaries = listOf(first, second).map { request -> async { router.summarize(request) } }
                            router.validateApiKey(providerType = providerType, baseUrl = provider.baseUrl, apiKey = otherKey)
                            summaries.awaitAll().map { it.title } shouldBe List(2) { title }
                        }
                        server.requests.size shouldBe 3
                        assertAuthentication(
                            request = server.requests.single { it.method == "GET" },
                            providerType = providerType,
                            key = otherKey,
                        )
                        for (request in listOf(first, second)) {
                            val captured =
                                server.requests.single {
                                    it.method == "POST" &&
                                        requestModel(request = it, providerType = providerType) == request.model
                                }
                            assertAuthentication(request = captured, providerType = providerType, key = request.apiKey)
                        }
                    }
                }

                if (basePath == "/gateway//v1") {
                    for (useSdk in listOf(true, false)) {
                        When("운영 어댑터의 벤더 오류를 처리하면 (useSdk=$useSdk)") {
                            lateinit var model: AvailableModel

                            beforeTest {
                                if (it.type != TestType.Test) {
                                    return@beforeTest
                                }
                                model = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!))
                            }

                            Then("키 등록이 명시적 진단을 반환하고 기존 키를 유지한다") {
                                val config =
                                    MapApplicationConfig(
                                        "$configPrefix.useSdk" to useSdk.toString(),
                                        "$configPrefix.sdkEndpoint" to (server.baseUrl + basePath),
                                    )
                                val app = providerApplication(config)
                                try {
                                    val service = koinGet<PutAiUserProviderService>(app.koin)
                                    val existingKey = "test-${RandomFixture.randomId()}"
                                    service.putAiUserProvider(loginId = user.id!!, request = registration(existingKey))
                                    val previous = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                                    val invalidKey = "test-${RandomFixture.randomId()}"
                                    val invalidKeyBody = invalidKeyError(providerType = providerType, key = invalidKey)
                                    val summaryOnlyError = """{"error":{"code":"context_length_exceeded"}}"""
                                    val errors =
                                        listOf(
                                            Triple(400, invalidKeyBody, ErrorCode.AI_API_KEY_INVALID),
                                            Triple(
                                                429,
                                                quotaError(providerType = providerType, key = invalidKey),
                                                ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED,
                                            ),
                                            Triple(429, """{"error":{"message":"$invalidKey"}}""", ErrorCode.AI_VENDOR_RATE_LIMITED),
                                            Triple(
                                                503,
                                                """{"error":{"message":"$invalidKey"}}""",
                                                ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                                            ),
                                        ) +
                                            if (providerType == AiProviderType.OPENAI) {
                                                listOf(Triple(400, summaryOnlyError, ErrorCode.AI_VENDOR_REQUEST_REJECTED))
                                            } else {
                                                emptyList()
                                            }
                                    for ((status, body, expected) in errors) {
                                        server.reply = { AiProviderServer.Reply(status = status, body = body) }
                                        val replace =
                                            suspend {
                                                service.putAiUserProvider(loginId = user.id!!, request = registration(invalidKey))
                                            }

                                        val error = shouldThrowWithMessage<BusinessException>(expected.message) { replace() }
                                        error.errorCode shouldBe expected
                                        error.cause shouldBe null
                                        val saved = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                                        saved.id shouldBe previous.id
                                        saved.apiKey shouldBe previous.apiKey
                                    }
                                    val path =
                                        (
                                            if (useSdk) {
                                                basePath
                                            } else {
                                                "/stored$basePath"
                                            }
                                        ) + "/models"
                                    server.requests.map { it.method to it.path } shouldBe List(errors.size + 1) { "GET" to path }
                                } finally {
                                    app.close()
                                }
                            }

                            Then("빈 프롬프트는 전송하지 않고 워커는 진단별 실패를 저장한다") {
                                val config =
                                    MapApplicationConfig(
                                        "$configPrefix.useSdk" to useSdk.toString(),
                                        "$configPrefix.sdkEndpoint" to (server.baseUrl + basePath),
                                    )
                                val app = providerApplication(config)
                                try {
                                    val apiKey = "test-${RandomFixture.randomId()}"
                                    val router = koinGet<AiClientRouter>(app.koin)
                                    val summarizeBlank = suspend { router.summarize(summaryRequest(apiKey).copy(prompt = "  ")) }
                                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_SUMMARY_INPUT_EMPTY.message) { summarizeBlank() }
                                    server.requests shouldBe emptyList()
                                    val registered =
                                        koinGet<PutAiUserProviderService>(app.koin)
                                            .putAiUserProvider(loginId = user.id!!, request = registration(apiKey))
                                    val title = RandomFixture.randomSentenceWithMax(30)
                                    val summary = RandomFixture.randomSentenceWithMax(100)
                                    val output =
                                        buildJsonObject {
                                            put("title", title)
                                            put("summary", summary)
                                        }.toString()
                                    val tokens = RandomFixture.randomInt(1, 50)
                                    val success = summaryEnvelope(providerType = providerType, text = output, tokens = tokens)
                                    server.reply = { AiProviderServer.Reply(body = success) }
                                    router.summarize(summaryRequest(apiKey)).usedTokens shouldBe tokens
                                    val text = JsonPrimitive(output)
                                    val vendorBody =
                                        if (providerType == AiProviderType.OPENAI) {
                                            """{
                                                "status":"completed",
                                                "output":[{"type":"message","status":"completed","content":[{"type":"output_text","text":$text}]}],
                                                "usage":{"total_tokens":$tokens}
                                            }"""
                                        } else {
                                            """{
                                                "candidates":[{"finishReason":"STOP",
                                                  "content":{"parts":[{"thought":true,"text":"$apiKey"},{"text":$text}]}}],
                                                "usageMetadata":{"totalTokenCount":$tokens}
                                            }"""
                                        }
                                    server.reply = { AiProviderServer.Reply(body = vendorBody) }
                                    val summaryResult = router.summarize(summaryRequest(apiKey))
                                    summaryResult.rawResponse shouldBe output
                                    summaryResult.usedTokens shouldBe tokens

                                    for ((reply, expected) in vendorSummaryFailures(
                                        providerType = providerType,
                                        success = success,
                                        summaryText = output,
                                        echoedKey = apiKey,
                                    )) {
                                        val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                                        val linkId = link.id!!
                                        server.reply = { reply }
                                        koinGet<UpdateLinkAiSummaryService>(app.koin).updateLinkAiSummary(
                                            loginId = user.id!!,
                                            request =
                                                AiSummaryRequest(
                                                    id = linkId,
                                                    userProviderId = registered.id,
                                                    modelId = model.id!!,
                                                    url = link.url,
                                                ),
                                        )
                                        val jobId = withTimeout(2_000) { koinGet<Channel<Long>>(app.koin).receive() }
                                        val proceed = suspend { koinGet<AiSummaryWorker>(app.koin).proceed(jobId) }
                                        withTimeout(5_000) { shouldNotThrow<BusinessException> { proceed() } }

                                        val job = jobRepository.findById(jobId)!!
                                        job.status shouldBe AiJobStatus.F
                                        job.response shouldBe null
                                        val savedLink = linkRepository.findById(linkId)!!
                                        savedLink.status shouldBe LinkStatus.F
                                        savedLink.summary shouldBe expected.message
                                        todoRepository.findAllByLinkId(linkId) shouldBe emptyList()
                                    }
                                    usageRepository.findByUserIdAndProviderIdAndUsageDate(
                                        userProviderId = registered.id,
                                        modelId = model.id!!,
                                        usageDate = LocalDate.now(ZoneOffset.UTC),
                                    ) shouldBe null
                                } finally {
                                    app.close()
                                }
                            }
                        }
                    }

                    When("운영 설정에서 해당 공급자의 SDK를 끄면") {
                        Then("그 공급자만 Ktor 어댑터로 전환하고 저장된 주소로 요청한다") {
                            val sdkRouter = koinGet<AiClientRouter>(application.koin)
                            (sdkRouter.requireSupported(providerType)::class) shouldBe
                                if (providerType == AiProviderType.OPENAI) {
                                    OpenAiSdkClient::class
                                } else {
                                    GeminiSdkClient::class
                                }

                            val fallback = providerApplication(MapApplicationConfig("$configPrefix.useSdk" to "false"))
                            try {
                                val router = koinGet<AiClientRouter>(fallback.koin)
                                (router.requireSupported(providerType)::class) shouldBe
                                    if (providerType == AiProviderType.OPENAI) {
                                        OpenAiCurlClient::class
                                    } else {
                                        GeminiCurlClient::class
                                    }
                                val otherType =
                                    if (providerType == AiProviderType.OPENAI) {
                                        AiProviderType.GEMINI
                                    } else {
                                        AiProviderType.OPENAI
                                    }
                                router.requireSupported(otherType)::class shouldBe sdkRouter.requireSupported(otherType)::class

                                val apiKey = "test-${RandomFixture.randomId()}"
                                val title = RandomFixture.randomSentenceWithMax(30)
                                val output =
                                    buildJsonObject {
                                        put("title", title)
                                        put("summary", title)
                                    }.toString()
                                val tokens = RandomFixture.randomInt(1, 50)
                                server.reply = {
                                    if (it.method == "GET") {
                                        AiProviderServer.Reply()
                                    } else {
                                        AiProviderServer.Reply(
                                            body = summaryEnvelope(providerType = providerType, text = output, tokens = tokens),
                                        )
                                    }
                                }
                                val result =
                                    koinGet<PutAiUserProviderService>(fallback.koin)
                                        .putAiUserProvider(loginId = user.id!!, request = registration(apiKey))
                                val saved = userProviderRepository.findById(result.id)!!
                                koinGet<ApiKeyCipher>(fallback.koin).decrypt(saved.apiKey) shouldBe apiKey
                                val request = summaryRequest(apiKey)
                                val response = router.summarize(request)
                                response.title shouldBe title
                                response.usedTokens shouldBe tokens
                                val summaryPath =
                                    if (providerType ==
                                        AiProviderType.OPENAI
                                    ) {
                                        "responses"
                                    } else {
                                        "models/${request.model}:generateContent"
                                    }
                                server.requests.map { it.method to it.path } shouldBe
                                    listOf("GET" to "/stored$basePath/models", "POST" to "/stored$basePath/$summaryPath")
                                for (captured in server.requests) {
                                    if (providerType == AiProviderType.OPENAI) {
                                        assertAuthentication(request = captured, providerType = providerType, key = apiKey)
                                    } else {
                                        captured.query shouldBe "key=$apiKey"
                                        captured.googleApiKey shouldBe null
                                    }
                                }
                            } finally {
                                fallback.close()
                            }
                        }
                    }
                }

                if (basePath == "/v1" || basePath == "/v1beta") {
                    When("모델 목록이 200 헤더 뒤에 본문을 일부만 보내고 연결을 끊으면") {
                        Then("한 번의 호출만 하고 키를 등록하지 않는다") {
                            server.reply = { AiProviderServer.Reply(body = "{", afterBodyPrefix = {}) }
                            val register = suspend { register("test-${RandomFixture.randomId()}") }
                            shouldThrowWithMessage<BusinessException>(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE.message) { register() }
                            server.requests.map { it.method } shouldBe listOf("GET")
                            userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!) shouldBe null
                        }
                    }

                    When("응답을 기다리는 키 검증과 요약을 취소하면") {
                        Then("연결이 닫히고 재요청과 키 저장이 발생하지 않는다") {
                            val router = koinGet<AiClientRouter>(application.koin)
                            val key = "test-${RandomFixture.randomId()}"
                            val summary = summaryRequest(key)
                            val calls = listOf<suspend () -> Unit>({ register(key) }, { router.summarize(summary) })
                            for (call in calls) {
                                val arrived = CompletableDeferred<Unit>()
                                val release = CountDownLatch(1)
                                server.reply = {
                                    arrived.complete(Unit)
                                    release.await(10, TimeUnit.SECONDS)
                                    AiProviderServer.Reply(body = " ".repeat(4 * 1024 * 1024))
                                }
                                try {
                                    coroutineScope {
                                        val pending = launch { call() }
                                        withTimeout(3_000) { arrived.await() }
                                        withTimeout(3_000) { pending.cancelAndJoin() }
                                    }
                                } finally {
                                    release.countDown()
                                }
                                withContext(Dispatchers.IO) { server.writes.poll(3, TimeUnit.SECONDS) } shouldBe false
                            }
                            server.requests.map { it.method } shouldBe listOf("GET", "POST")
                            userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!) shouldBe null
                        }
                    }

                    When("키 검증과 요약이 응답 본문을 읽는 도중 취소되면") {
                        Then("연결을 닫고 재요청하거나 키·요약·사용량을 저장하지 않는다") {
                            val apiKey = "test-${RandomFixture.randomId()}"
                            val chunk = " ".repeat(4 * 1024 * 1024)

                            suspend fun cancelDuringBody(call: suspend () -> Unit) {
                                val prefixSent = CompletableDeferred<Unit>()
                                val release = CountDownLatch(1)
                                server.reply = {
                                    AiProviderServer.Reply(
                                        body = chunk,
                                        afterBodyPrefix = {
                                            prefixSent.complete(Unit)
                                            release.await(10, TimeUnit.SECONDS)
                                        },
                                        remainingBody = chunk,
                                    )
                                }
                                try {
                                    coroutineScope {
                                        val pending = launch { call() }
                                        withTimeout(3_000) { prefixSent.await() }
                                        withTimeout(3_000) { pending.cancelAndJoin() }
                                    }
                                } finally {
                                    release.countDown()
                                }
                                withContext(Dispatchers.IO) { server.writes.poll(3, TimeUnit.SECONDS) } shouldBe false
                            }

                            cancelDuringBody { register(apiKey) }
                            userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!) shouldBe null

                            val key =
                                userProviderRepository.insert(
                                    UserProvider(
                                        userId = user.id!!,
                                        providerId = provider.id!!,
                                        apiKey = koinGet<ApiKeyCipher>(application.koin).encrypt(apiKey),
                                    ),
                                )
                            val model = modelRepository.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!))
                            val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                            koinGet<UpdateLinkAiSummaryService>(application.koin).updateLinkAiSummary(
                                loginId = user.id!!,
                                request = AiSummaryRequest(id = link.id!!, userProviderId = key.id!!, modelId = model.id!!, url = link.url),
                            )
                            val jobId = withTimeout(2_000) { koinGet<Channel<Long>>(application.koin).receive() }
                            val worker = koinGet<AiSummaryWorker>(application.koin)
                            cancelDuringBody { worker.proceed(jobId) }

                            server.requests.map { it.method } shouldBe listOf("GET", "POST")
                            val job = jobRepository.findById(jobId)!!
                            job.status shouldBe AiJobStatus.P
                            job.startedAt shouldNotBe null
                            job.response shouldBe null
                            val savedLink = linkRepository.findById(link.id!!)!!
                            savedLink.status shouldBe LinkStatus.G
                            savedLink.summary shouldBe link.summary
                            todoRepository.findAllByLinkId(link.id!!) shouldBe emptyList()
                            usageRepository.findByUserIdAndProviderIdAndUsageDate(
                                userProviderId = key.id!!,
                                modelId = model.id!!,
                                usageDate = LocalDate.now(ZoneOffset.UTC),
                            ) shouldBe null
                        }
                    }

                    When("공급자가 운영 요청 제한 시간 안에 응답하지 않으면") {
                        Then("검증과 요약이 실패하고 후속 요청과 키 저장이 발생하지 않는다") {
                            val release = CountDownLatch(1)
                            server.reply = { request ->
                                if (request.method == "GET") {
                                    AiProviderServer.Reply(body = "{", afterBodyPrefix = { release.await(30, TimeUnit.SECONDS) })
                                } else {
                                    release.await(30, TimeUnit.SECONDS)
                                    AiProviderServer.Reply()
                                }
                            }
                            val key = "test-${RandomFixture.randomId()}"
                            val register = suspend { register(key) }
                            try {
                                withTimeout(20_000) {
                                    val summary =
                                        async {
                                            runCatching { koinGet<AiClientRouter>(application.koin).summarize(summaryRequest(key)) }
                                                .exceptionOrNull() as BusinessException
                                        }
                                    shouldThrowWithMessage<BusinessException>(
                                        ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE.message,
                                    ) { register() }
                                    summary.await().errorCode shouldBe ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE
                                }
                                server.requests.map { it.method }.sorted() shouldBe listOf("GET", "POST")
                                userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!) shouldBe null
                            } finally {
                                release.countDown()
                            }
                        }
                    }
                }
            }
        }
    })

private fun quotaError(
    providerType: AiProviderType,
    key: String,
): String =
    if (providerType == AiProviderType.OPENAI) {
        """{"error":{"message":"provider echoed $key","code":"insufficient_quota"}}"""
    } else {
        """{"error":{"message":"provider echoed $key","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"QUOTA_EXCEEDED"}]}}"""
    }

private fun assertAuthentication(
    request: AiProviderServer.Request,
    providerType: AiProviderType,
    key: String,
) {
    if (providerType == AiProviderType.OPENAI) {
        request.authorization shouldBe "Bearer $key"
        request.googleApiKey shouldBe null
    } else {
        request.googleApiKey shouldBe key
        request.authorization shouldBe null
    }
}

private fun requestModel(
    request: AiProviderServer.Request,
    providerType: AiProviderType,
): String =
    if (providerType == AiProviderType.OPENAI) {
        request.body!!
            .getValue("model")
            .jsonPrimitive.content
    } else {
        request.path.substringAfterLast("/").substringBefore(":")
    }

private fun assertSummaryRequest(
    request: AiProviderServer.Request,
    providerType: AiProviderType,
    basePath: String,
    prompt: String,
) {
    request.method shouldBe "POST"
    val body = request.body!!
    if (providerType == AiProviderType.OPENAI) {
        request.path shouldBe "$basePath/responses"
        body["instructions"]!!.jsonPrimitive.content shouldBe AiSummarySpec.systemInstruction
        val message = body["input"]!!.jsonArray.single().jsonObject
        message["role"]!!.jsonPrimitive.content shouldBe "user"
        val content = message["content"]!!.jsonArray.single().jsonObject
        content["type"]!!.jsonPrimitive.content shouldBe "input_text"
        content["text"]!!.jsonPrimitive.content shouldBe prompt
        body["tools"]!!
            .jsonArray
            .single()
            .jsonObject["type"]!!
            .jsonPrimitive.content shouldBe "web_search"
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        format["type"]!!.jsonPrimitive.content shouldBe "json_schema"
        format["name"]!!.jsonPrimitive.content shouldBe AiSummarySpec.RESPONSE_SCHEMA_NAME
        format["strict"]!!.jsonPrimitive.content shouldBe "true"
        format["schema"] shouldBe AiSummarySpec.openAiJsonSchema
    } else {
        request.path shouldBe "$basePath/models/${requestModel(request = request, providerType = providerType)}:generateContent"
        val system =
            body["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray
                .single()
                .jsonObject
        system["text"]!!.jsonPrimitive.content shouldBe AiSummarySpec.systemInstruction
        val content =
            body["contents"]!!
                .jsonArray
                .single()
                .jsonObject["parts"]!!
                .jsonArray
                .single()
                .jsonObject
        content["text"]!!.jsonPrimitive.content shouldBe prompt
        body["tools"]!!.jsonArray.single().jsonObject["urlContext"] shouldBe JsonObject(emptyMap())
        val config = body["generationConfig"]!!.jsonObject
        config["responseMimeType"]!!.jsonPrimitive.content shouldBe "application/json"
        config["responseJsonSchema"] shouldBe AiSummarySpec.geminiResponseSchema
    }
}

private fun summaryEnvelope(
    providerType: AiProviderType,
    text: String,
    tokens: Int,
    laterText: String? = "later",
): String {
    val parts =
        buildJsonArray {
            add(buildJsonObject { put("text", text) })
            laterText?.let { add(buildJsonObject { put("text", it) }) }
        }
    return if (providerType == AiProviderType.OPENAI) {
        """{"output":[{},{"content":[{"text":null},{"text":" "}]},{"content":$parts}],"usage":{"total_tokens":$tokens}}"""
    } else {
        val first =
            buildJsonArray {
                add(buildJsonObject { put("text", JsonNull) })
                add(buildJsonObject { put("text", " ") })
                parts.forEach(::add)
            }
        """{"candidates":[{"content":{"parts":$first}},{"content":{"parts":[{"text":"next"}]}}],"usageMetadata":{"totalTokenCount":$tokens}}"""
    }
}

private fun vendorSummaryFailures(
    providerType: AiProviderType,
    success: String,
    summaryText: String,
    echoedKey: String,
): List<Pair<AiProviderServer.Reply, ErrorCode>> {
    val envelope = Json.parseToJsonElement(success).jsonObject
    val openAi = providerType == AiProviderType.OPENAI
    val usageField =
        if (openAi) {
            "usage"
        } else {
            "usageMetadata"
        }
    val totalField =
        if (openAi) {
            "total_tokens"
        } else {
            "totalTokenCount"
        }
    val invalidSummary = summaryEnvelope(providerType = providerType, text = "{broken $echoedKey", tokens = 0)
    val invalidTime =
        summaryEnvelope(
            providerType = providerType,
            text = """{"title":"title","summary":"summary","todos":[{"title":"action","reminderAt":"$echoedKey"}]}""",
            tokens = 0,
        )
    val invalidUsage =
        listOf(JsonPrimitive(-1), JsonPrimitive(2147483648L), JsonPrimitive("bad $echoedKey")).map { invalid ->
            val usage = buildJsonObject { put(totalField, invalid) }
            val body = JsonObject(envelope + (usageField to usage)).toString()
            body to ErrorCode.AI_SUMMARY_USAGE_INVALID
        }
    val common =
        listOf(
            "" to ErrorCode.AI_EMPTY_RESPONSE,
            "broken $echoedKey" to ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
            invalidSummary to ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
            invalidTime to ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
        ) + invalidUsage
    val diagnostics =
        if (openAi) {
            val refusal = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"$echoedKey"}]}]}"""
            val limit = buildJsonObject { put("reason", "max_output_tokens") }
            val incomplete = JsonObject(envelope + ("incomplete_details" to limit)).toString()
            val filtered = """{"status":"incomplete","incomplete_details":{"reason":"content_filter"}}"""
            val serverFailure = """{"status":"failed","error":{"code":"server_error","message":"$echoedKey"}}"""
            val wrongRegion = """{"status":"failed","error":{"code":"data_residency_mismatch","message":"$echoedKey"}}"""
            val partial = """{"output":[
                {"type":"message","status":"incomplete","content":[{"type":"output_text","text":${JsonPrimitive(summaryText)}}]}
            ]}"""
            val unfinishedLegacy =
                listOf("incomplete", "in_progress").map { status ->
                    val text = JsonPrimitive(summaryText)
                    val body = """{"output":[{"status":"$status","content":[{"text":$text}]}]}"""
                    body to ErrorCode.AI_SUMMARY_INCOMPLETE
                }
            listOf(
                refusal to ErrorCode.AI_SUMMARY_REFUSED,
                incomplete to ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED,
                filtered to ErrorCode.AI_SUMMARY_OUTPUT_BLOCKED,
                """{"status":"cancelled","output":[]}""" to ErrorCode.AI_SUMMARY_INCOMPLETE,
                serverFailure to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                wrongRegion to ErrorCode.AI_VENDOR_REQUEST_REJECTED,
                partial to ErrorCode.AI_SUMMARY_INCOMPLETE,
            ) + unfinishedLegacy
        } else {
            val candidate =
                envelope
                    .getValue("candidates")
                    .jsonArray
                    .first()
                    .jsonObject
            val blocked = """{"promptFeedback":{"blockReason":"SAFETY","blockReasonMessage":"$echoedKey"}}"""
            val unexpectedError = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"$echoedKey"}}"""
            listOf(
                blocked to ErrorCode.AI_SUMMARY_PROMPT_BLOCKED,
                unexpectedError to ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
                """{"candidates":[]}""" to ErrorCode.AI_EMPTY_RESPONSE,
                """{"candidates":[{"finishReason":"RECITATION"}]}""" to ErrorCode.AI_SUMMARY_OUTPUT_BLOCKED,
                """{"candidates":[{"finishReason":"OTHER"}]}""" to ErrorCode.AI_SUMMARY_INCOMPLETE,
                """{"candidates":[{"finishReason":"MALFORMED_RESPONSE"}]}""" to ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
                JsonObject(
                    envelope + (
                        "candidates" to
                            buildJsonArray {
                                add(JsonObject(candidate + ("finishReason" to JsonPrimitive("MAX_TOKENS"))))
                            }
                    ),
                ).toString() to ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED,
            )
        }
    val replies = (common + diagnostics).map { (body, code) -> AiProviderServer.Reply(body = body) to code }
    return if (openAi) {
        replies + (
            AiProviderServer.Reply(status = 400, body = """{"error":{"code":"context_length_exceeded"}}""") to
                ErrorCode.AI_SUMMARY_INPUT_LIMIT_REACHED
        )
    } else {
        replies
    }
}

private fun invalidKeyError(
    providerType: AiProviderType,
    key: String,
): String =
    if (providerType == AiProviderType.OPENAI) {
        """{"error":{"code":"invalid_api_key","message":"$key"}}"""
    } else {
        """{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}],"message":"$key"}}"""
    }
