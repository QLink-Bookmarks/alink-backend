package com.qlink.ai.service

import com.qlink.ai.client.AiApiKeyValidationException
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummaryClientRequest
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
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
import com.qlink.di.externalModule
import com.qlink.di.pluginModule
import com.qlink.di.repositoryModule
import com.qlink.di.serviceModule
import com.qlink.di.transactionModule
import com.qlink.di.workerModule
import com.qlink.link.domain.Link
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
        lateinit var application: KoinApplication

        beforeSpec {
            val config = ServiceTestEnvironment.applicationConfig()
            application =
                koinApplication {
                    modules(
                        module { single { database } },
                        transactionModule(),
                        repositoryModule(),
                        pluginModule(config),
                        externalModule(config = config, s3Config = ServiceTestEnvironment.s3Config(environment = "dev")),
                        serviceModule(),
                        workerModule(config),
                    )
                }
        }

        afterSpec { application.close() }

        for ((providerType, basePath) in listOf(
            AiProviderType.OPENAI to "/v1",
            AiProviderType.GEMINI to "/v1beta",
            AiProviderType.GEMINI to "",
        )) {
            Given("$providerType ${basePath.ifEmpty { "/" }} 운영 공급자에 키를 등록하고 요약을 요청하는 사용자") {
                lateinit var server: AiProviderServer
                lateinit var provider: AiProvider
                lateinit var user: User

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    server = AiProviderServer()
                    provider =
                        providerRepository.insert(
                            AiProvider(
                                type = providerType,
                                baseUrl = server.baseUrl + basePath,
                                activeAt = RandomFixture.randomPastInstant(),
                            ),
                        )
                    user = userRepository.insert(UserFixture.createRandomValidUser())
                }

                afterTest { if (it.a.type == TestType.Test) server.close() }

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
                        priorIds.size shouldBe 1
                        server.requests.map { it.method to it.path } shouldBe List(4) { "GET" to "$basePath/models" }
                        server.requests
                            .map { it.connection }
                            .toSet()
                            .size shouldBe 1
                    }
                }

                When("공급자가 등록을 거부하면") {
                    Then("본래 HTTP 상태를 오류 코드로 변환하고 기존 암호문을 유지한다") {
                        AiProviderServer().use { destination ->
                            val previousKey = "test-${RandomFixture.randomId()}"
                            register(previousKey)
                            val previous = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                            val errors =
                                mapOf(
                                    401 to ErrorCode.AI_API_KEY_INVALID,
                                    403 to ErrorCode.AI_API_KEY_INVALID,
                                    503 to ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE,
                                    429 to ErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                                    307 to ErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                                )
                            for ((status, code) in errors) {
                                server.reply = {
                                    AiProviderServer.Reply(
                                        status = status,
                                        body = "not-json",
                                        location = destination.baseUrl.takeIf { status == 307 },
                                        retryAfter = "0".takeIf { status == 503 },
                                    )
                                }
                                val replace = suspend { register("test-${RandomFixture.randomId()}") }

                                val error = shouldThrowWithMessage<BusinessException>(code.message) { replace() }
                                (error.cause as AiApiKeyValidationException).statusCode shouldBe status
                                val saved = userProviderRepository.findByUserIdAndProviderId(user.id!!, provider.id!!)!!
                                saved.id shouldBe previous.id
                                saved.apiKey shouldBe previous.apiKey
                            }
                            server.requests.map { it.method to it.path } shouldBe List(errors.size + 1) { "GET" to "$basePath/models" }
                            val router = koinGet<AiClientRouter>(application.koin)
                            val summary = summaryRequest(previousKey)
                            runCatching { router.summarize(summary) }.isFailure shouldBe true
                            server.requests.count { it.method == "POST" } shouldBe 1
                            destination.requests shouldBe emptyList()
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
                        if (it.type != TestType.Test) return@beforeTest
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

                When("공급자가 요약 사용량 일부를 생략하거나 유효한 요약을 보내지 않으면") {
                    Then("사용량 기본값과 빈 요약 오류를 기존 방식으로 처리한다") {
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
                        val usageField = if (providerType == AiProviderType.OPENAI) "usage" else "usageMetadata"
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
                            val summarize = suspend { router.summarize(request) }
                            shouldThrowWithMessage<BusinessException>(ErrorCode.AI_EMPTY_RESPONSE.message) { summarize() }
                        }

                        server.reply =
                            { AiProviderServer.Reply(body = summaryEnvelope(providerType = providerType, text = "{invalid", tokens = 0)) }
                        runCatching { router.summarize(request) }.isFailure shouldBe true
                    }
                }

                When("서로 다른 키로 요약과 검증을 동시에 호출하면") {
                    Then("같은 주소와 다른 주소 모두에서 각 요청에 자기 키만 전달된다") {
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
                        AiProviderServer().use { other ->
                            other.reply = server.reply
                            val remote = summaryRequest("test-${RandomFixture.randomId()}").copy(baseUrl = other.baseUrl + basePath)
                            coroutineScope {
                                val summaries = listOf(first, second, remote).map { request -> async { router.summarize(request) } }
                                router.validateApiKey(providerType = providerType, baseUrl = provider.baseUrl, apiKey = otherKey)
                                summaries.awaitAll().map { it.title } shouldBe List(3) { title }
                            }
                            assertAuthentication(request = other.requests.single(), providerType = providerType, key = remote.apiKey)
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

                if (basePath.isNotEmpty()) {
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

                    When("공급자가 운영 요청 제한 시간 안에 응답하지 않으면") {
                        Then("검증과 요약이 실패하고 후속 요청과 키 저장이 발생하지 않는다") {
                            val release = CountDownLatch(1)
                            server.reply = {
                                release.await(30, TimeUnit.SECONDS)
                                AiProviderServer.Reply()
                            }
                            val key = "test-${RandomFixture.randomId()}"
                            val register = suspend { register(key) }
                            try {
                                withTimeout(20_000) {
                                    val summary =
                                        async {
                                            runCatching { koinGet<AiClientRouter>(application.koin).summarize(summaryRequest(key)) }
                                        }
                                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.message) { register() }
                                    summary.await().isFailure shouldBe true
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
