package com.qlink.ai.client

import com.google.genai.errors.GenAiIOException
import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.AiCompatibilityFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

class AiSdkCompatibilityTest :
    BehaviorSpec({
        listOf(AiProviderType.OPENAI, AiProviderType.GEMINI).forEach { provider ->
            Given("$provider Ktor와 공식 SDK") {
                When("버전 경로와 요청 데이터를 대조하면") {
                    val basePaths = if (provider == AiProviderType.OPENAI) listOf("/v1") else listOf("/v1beta", "")
                    basePaths.forEach { basePath ->
                        Then("${basePath.ifEmpty { "루트" }}에서 요약과 모델 목록의 실제 요청을 비교한다") {
                            val host = if (provider == AiProviderType.OPENAI) "api.openai.com" else "generativelanguage.googleapis.com"
                            provider.isAllowedBaseUrl("https://$host$basePath") shouldBe true
                            val input = summaryRequest(provider = provider, baseUrl = "")
                            val summary = summaryEnvelope(provider)
                            compare(provider) { client, server, sdk ->
                                val baseUrl = server.baseUrl + basePath
                                val request = input.copy(baseUrl = baseUrl)
                                server.reply = {
                                    if (it.method == "GET") {
                                        AiCompatibilityServer.Reply(body = """{"data":[],"models":[]}""")
                                    } else {
                                        AiCompatibilityServer.Reply(body = summary)
                                    }
                                }

                                client.summarize(request)
                                client.validateApiKey(AiApiKeyValidationRequest(baseUrl = baseUrl, apiKey = request.apiKey))
                                val pathLabel = basePath.removePrefix("/").ifEmpty { "root" }
                                server.saveCapture("${provider.name.lowercase()}-$pathLabel-${label(sdk)}")

                                val (post, get) = server.requests
                                server.requests.map { it.method } shouldBe listOf("POST", "GET")
                                get.path shouldBe "$basePath/models"
                                assertTrialKey(post, provider, sdk, request.apiKey)
                                assertTrialKey(get, provider, sdk, request.apiKey)
                                val body = post.body!!.jsonObject
                                if (provider == AiProviderType.OPENAI) {
                                    post.path shouldBe "$basePath/responses"
                                    body shouldBe expectedOpenAiBody(request)
                                } else {
                                    post.path shouldBe "$basePath/models/${request.model}:generateContent"
                                    assertGeminiBody(body, request, sdk)
                                }
                            }
                        }
                    }
                }

                When("여러 출력·텍스트와 사용량 조합을 받으면") {
                    Then("첫 유효 텍스트를 선택하고 요약 결과 전체와 사용량이 현행과 같다") {
                        val fixture = AiCompatibilityFixture.summary()
                        val wrapped = "```json\n앞\n${fixture.rawResponse}\n뒤\n```"
                        val idOnlyJson = Json.parseToJsonElement(fixture.rawResponse).jsonObject
                        val idOnly = JsonObject(idOnlyJson - "linkId").toString()
                        val idOnlyResult =
                            fixture.copy(
                                linkId =
                                    idOnlyJson
                                        .getValue("id")
                                        .jsonPrimitive.content
                                        .toLong(),
                                rawResponse = idOnly,
                            )
                        val tokenField = if (provider == AiProviderType.OPENAI) "total_tokens" else "totalTokenCount"
                        val tokenCount = RandomFixture.randomId().mod(1_000) + 1
                        val usageCases =
                            listOf(
                                null to fixture.copy(usedTokens = wrapped.length),
                                buildJsonObject {} to fixture,
                                buildJsonObject { put(tokenField, 0) } to fixture,
                                buildJsonObject { put(tokenField, tokenCount) } to fixture.copy(usedTokens = tokenCount),
                            )
                        compare(provider) { client, server, sdk ->
                            val request = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                            val firstTexts = listOf(" \n ", "  $wrapped \n ", "{}")
                            val groups =
                                if (provider == AiProviderType.OPENAI) {
                                    listOf(emptyList(), listOf("   "), firstTexts, listOf("{}"))
                                } else {
                                    listOf(firstTexts, listOf("{}"))
                                }
                            for ((usage, expected) in usageCases) {
                                server.reply = {
                                    AiCompatibilityServer.Reply(
                                        body = AiCompatibilityFixture.envelope(provider = provider, groups = groups, usage = usage),
                                    )
                                }
                                client.summarize(request) shouldBe expected
                            }
                            server.reply = { AiCompatibilityServer.Reply(body = summaryEnvelope(provider, idOnly)) }
                            client.summarize(request) shouldBe idOnlyResult.copy(usedTokens = idOnly.length)
                            println(
                                "$provider ${label(
                                    sdk,
                                )} summary fields and usage: absent=${wrapped.length}, missing=0, zero=0, positive=$tokenCount",
                            )
                        }
                    }
                }

                When("유효 텍스트가 없거나 선택된 텍스트가 잘못된 JSON이면") {
                    Then("AI_EMPTY_RESPONSE와 파서의 실패 종류를 각각 보존한다") {
                        val outcomes =
                            compare(provider) { client, server, sdk ->
                                val request = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                                val emptyGroups =
                                    mutableListOf<List<List<String>>?>(null, emptyList(), listOf(emptyList()), listOf(listOf("  ")))
                                if (provider == AiProviderType.GEMINI) {
                                    emptyGroups.add(listOf(listOf("  "), listOf(AiCompatibilityFixture.summary().rawResponse)))
                                }
                                for (groups in emptyGroups) {
                                    server.reply = {
                                        AiCompatibilityServer.Reply(
                                            body = AiCompatibilityFixture.envelope(provider = provider, groups = groups),
                                        )
                                    }
                                    shouldThrow<BusinessException> { client.summarize(request) }.errorCode shouldBe
                                        ErrorCode.AI_EMPTY_RESPONSE
                                }
                                server.reply = { AiCompatibilityServer.Reply(body = summaryEnvelope(provider, "{broken}")) }
                                val failure = runCatching { client.summarize(request) }.exceptionOrNull()!!
                                println("$provider ${label(sdk)} invalid model JSON: ${failure.javaClass.name}")
                                failure.javaClass
                            }
                        outcomes.last() shouldBe outcomes.first()
                    }
                }

                When("첫 파트의 텍스트 값이 명시적 null이면") {
                    Then("다음 텍스트를 선택하거나 SDK의 역직렬화 차이를 기록한다") {
                        val fixture = AiCompatibilityFixture.summary()
                        val body = AiCompatibilityFixture.envelope(provider = provider, groups = listOf(listOf(null, fixture.rawResponse)))
                        compare(provider) { client, server, sdk ->
                            server.reply = { AiCompatibilityServer.Reply(body = body) }
                            val request = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                            if (sdk && provider == AiProviderType.GEMINI) {
                                val failure = shouldThrow<GenAiIOException> { client.summarize(request) }
                                println("$provider ${label(sdk)} explicit null text: ${failure.javaClass.name}")
                            } else {
                                client.summarize(request) shouldBe fixture.copy(usedTokens = fixture.rawResponse.length)
                            }
                        }
                    }
                }

                When("모델 목록의 상태와 본문 조합을 확인하면") {
                    Then("상태를 복원하고 SDK가 요구하는 2xx 본문 및 페이지 동작의 차이를 기록한다") {
                        compare(provider) { client, server, sdk ->
                            val request = validationRequest(server)
                            val cases =
                                linkedMapOf(
                                    "empty-body" to "",
                                    "empty-models" to """{"object":"list","data":[],"models":[]}""",
                                    "invalid-models" to "not-json",
                                    "next-page" to
                                        """{"object":"list","data":[],"models":[{"name":"models/test-model"}],"nextPageToken":"test-next"}""",
                                )
                            val successes =
                                cases.mapValues { (name, body) ->
                                    server.reply = { AiCompatibilityServer.Reply(body = body) }
                                    val before = server.requests.size
                                    val result = runCatching { client.validateApiKey(request) }
                                    server.requests.drop(before).map { it.method to it.path } shouldBe listOf("GET" to "/models")
                                    println("$provider ${label(sdk)} list $name: ${result.exceptionOrNull()?.javaClass?.name ?: "success"}")
                                    result.isSuccess
                                }
                            val expected =
                                if (provider == AiProviderType.GEMINI && sdk) listOf(true, true, false, true) else List(4) { true }
                            successes.values.toList() shouldBe expected
                            for (status in listOf(401, 403, 503, 429)) {
                                server.reply =
                                    { AiCompatibilityServer.Reply(status = status, body = """{"error":{"message":"test failure"}}""") }
                                val before = server.requests.size
                                shouldThrow<AiApiKeyValidationException> { client.validateApiKey(request) }.statusCode shouldBe status
                                server.requests.size - before shouldBe 1
                            }
                        }
                    }
                }

                When("두 작업이 다른 서버로의 리디렉션을 받으면") {
                    Then("최초 서버만 호출하고 키 검증에는 최초 HTTP 상태를 반환한다") {
                        compare(provider) { client, server, sdk ->
                            AiCompatibilityServer().use { destination ->
                                server.reply = { AiCompatibilityServer.Reply(status = 307, location = destination.baseUrl + it.path) }
                                val result =
                                    runCatching {
                                        client.summarize(summaryRequest(provider = provider, baseUrl = server.baseUrl))
                                    }
                                result.isFailure shouldBe true
                                val failure =
                                    shouldThrow<AiApiKeyValidationException> { client.validateApiKey(validationRequest(server)) }
                                failure.statusCode shouldBe 307
                                server.requests.map { it.method } shouldBe listOf("POST", "GET")
                                destination.requests.size shouldBe 0
                                println(
                                    "$provider ${label(
                                        sdk,
                                    )} redirect: summary=${result.exceptionOrNull()?.javaClass?.name}, validate=307, destination=0",
                                )
                            }
                        }
                    }
                }

                When("일시 실패가 후보 호출 중 발생하면") {
                    Then("각 후보의 HTTP 요청은 한 번이다") {
                        compare(provider) { client, server, sdk ->
                            val first = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                            val next = first.copy(model = "${first.model}-fallback")
                            val valid = summaryEnvelope(provider)
                            server.reply = {
                                if (model(provider, it) == first.model) {
                                    AiCompatibilityServer.Reply(status = 503, body = """{"error":{"message":"test unavailable"}}""")
                                } else {
                                    AiCompatibilityServer.Reply(body = valid)
                                }
                            }
                            val failure = runCatching { client.summarize(first) }.exceptionOrNull()!!
                            client.summarize(next)
                            server.requests.map { model(provider, it) } shouldBe listOf(first.model, next.model)
                            println("$provider ${label(sdk)} transient: ${failure.javaClass.name}, one request per candidate in order")
                        }
                    }
                }

                When("응답 헤더가 늦으면") {
                    listOf("POST", "GET").forEach { method ->
                        Then("$method 요청이 공통 시험 제한 시간으로 종료되고 후속 요청이 없다") {
                            val timeoutMillis = 700L
                            val body = probeResponse(provider, method)
                            compare(provider, timeoutMillis) { client, server, sdk ->
                                val release = CountDownLatch(1)
                                val transmitted = CompletableDeferred<Boolean>()
                                server.reply = {
                                    release.await(5, TimeUnit.SECONDS)
                                    AiCompatibilityServer.Reply(body = body)
                                }
                                server.onResponseWrite = { transmitted.complete(it) }
                                val request = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                                val mark = TimeSource.Monotonic.markNow()
                                val failure =
                                    try {
                                        runCatching {
                                            withTimeout(4_000) { executeProbe(client = client, method = method, request = request) }
                                        }.exceptionOrNull()!!
                                    } finally {
                                        release.countDown()
                                    }
                                val elapsed = mark.elapsedNow().inWholeMilliseconds
                                val observation = withTimeoutOrNull(2_000) { transmitted.await() }?.toString() ?: "pending"
                                val outcome = "${failure.javaClass.name}, cause=${failure.cause?.javaClass?.name}"
                                println(
                                    "$provider ${label(
                                        sdk,
                                    )} $method timeout: ${elapsed}ms, $outcome, server write=$observation, requests=1, follow-ups=0",
                                )
                                (elapsed in 500..3_999) shouldBe true
                                (failure is CancellationException) shouldBe false
                                server.requests.map { it.method } shouldBe listOf(method)
                            }
                        }
                    }
                }

                When("요청을 수신한 뒤 응답 전 호출 코루틴을 취소하면") {
                    listOf("POST", "GET").forEach { method ->
                        Then("$method 호출자 종료와 서버 응답 쓰기 결과를 관찰한다") {
                            val body = probeResponse(provider, method) + " ".repeat(8 * 1024 * 1024)
                            compare(provider) { client, server, sdk ->
                                val received = CompletableDeferred<Unit>()
                                val release = CountDownLatch(1)
                                val transmitted = CompletableDeferred<Boolean>()
                                server.reply = {
                                    received.complete(Unit)
                                    release.await(5, TimeUnit.SECONDS)
                                    AiCompatibilityServer.Reply(body = body)
                                }
                                server.onResponseWrite = { transmitted.complete(it) }
                                val request = summaryRequest(provider = provider, baseUrl = server.baseUrl)
                                coroutineScope {
                                    val completion = CompletableDeferred<Throwable?>()
                                    val job = launch { executeProbe(client = client, method = method, request = request) }
                                    job.invokeOnCompletion { completion.complete(it) }
                                    withTimeout(3_000) { received.await() }
                                    val mark = TimeSource.Monotonic.markNow()
                                    val endedBeforeRelease =
                                        try {
                                            job.cancel()
                                            withTimeoutOrNull(500) { completion.join() } != null
                                        } finally {
                                            release.countDown()
                                        }
                                    val cause = withTimeout(3_000) { completion.await() }
                                    val elapsed = mark.elapsedNow().inWholeMilliseconds
                                    val completedWrite = withTimeoutOrNull(2_000) { transmitted.await() }
                                    val observation = completedWrite?.toString() ?: "pending"
                                    println(
                                        "$provider ${label(sdk)} $method cancellation: ${elapsed}ms, ${cause?.javaClass?.name}, " +
                                            "caller ended before release=$endedBeforeRelease, server write=$observation, requests=1, follow-ups=0",
                                    )
                                    (cause is CancellationException) shouldBe true
                                    if (!sdk) completedWrite shouldBe false
                                    server.requests.map { it.method } shouldBe listOf(method)
                                }
                            }
                        }
                    }
                }

                When("두 사용자의 시험 키로 같은 목적지와 서로 다른 목적지를 호출하면") {
                    Then("순차·동시 요청의 키와 목적지를 대조하고 동일 목적지의 연결 수를 비교한다") {
                        compare(provider) { client, firstServer, sdk ->
                            AiCompatibilityServer().use { secondServer ->
                                val body = summaryEnvelope(provider)
                                val first = summaryRequest(provider = provider, baseUrl = firstServer.baseUrl)
                                val second = summaryRequest(provider = provider, baseUrl = firstServer.baseUrl)
                                val remote = second.copy(baseUrl = secondServer.baseUrl)
                                val reply: (AiCompatibilityServer.Request) -> AiCompatibilityServer.Reply = {
                                    when {
                                        it.method == "POST" -> {
                                            AiCompatibilityServer.Reply(body = body)
                                        }

                                        trialKey(
                                            it,
                                            provider,
                                            sdk,
                                        ) == first.apiKey -> {
                                            AiCompatibilityServer.Reply(body = """{"data":[],"models":[]}""")
                                        }

                                        else -> {
                                            AiCompatibilityServer.Reply(
                                                status = 403,
                                                body = """{"error":{"message":"test key rejected"}}""",
                                            )
                                        }
                                    }
                                }
                                firstServer.reply = reply
                                secondServer.reply = reply

                                suspend fun call(request: AiSummaryClientRequest) {
                                    client.summarize(request)
                                    val validation = AiApiKeyValidationRequest(baseUrl = request.baseUrl, apiKey = request.apiKey)
                                    if (request.apiKey == first.apiKey) {
                                        client.validateApiKey(validation)
                                    } else {
                                        shouldThrow<AiApiKeyValidationException> { client.validateApiKey(validation) }.statusCode shouldBe
                                            403
                                    }
                                }

                                call(first)
                                call(second)
                                val sequentialConnections =
                                    firstServer.requests
                                        .map { it.connection }
                                        .distinct()
                                        .size
                                coroutineScope { listOf(first, second).map { async { call(it) } }.awaitAll() }
                                call(first)
                                call(remote)
                                coroutineScope { listOf(first, remote).map { async { call(it) } }.awaitAll() }

                                val firstRequests = firstServer.requests
                                firstRequests.take(2).forEach { assertTrialKey(it, provider, sdk, first.apiKey) }
                                firstRequests.drop(2).take(2).forEach { assertTrialKey(it, provider, sdk, second.apiKey) }
                                val sameDestination = firstRequests.drop(4).take(4)
                                sameDestination.filter { model(provider, it) == first.model }.single().let {
                                    assertTrialKey(it, provider, sdk, first.apiKey)
                                }
                                sameDestination.filter { model(provider, it) == second.model }.single().let {
                                    assertTrialKey(it, provider, sdk, second.apiKey)
                                }
                                val keys = sameDestination.filter { it.method == "GET" }.map { trialKey(it, provider, sdk) }
                                keys.toSet() shouldBe setOf(first.apiKey, second.apiKey)
                                firstRequests.drop(8).size shouldBe 4
                                firstRequests.drop(8).forEach { assertTrialKey(it, provider, sdk, first.apiKey) }
                                secondServer.requests.map { it.method } shouldBe listOf("POST", "GET", "POST", "GET")
                                secondServer.requests.forEach { assertTrialKey(it, provider, sdk, remote.apiKey) }
                                println(
                                    "$provider ${label(
                                        sdk,
                                    )} isolation: first=${firstRequests.size}, second=${secondServer.requests.size}, sequential connections=$sequentialConnections",
                                )
                                sequentialConnections shouldBe if (sdk) 1 else 4
                            }
                        }
                    }
                }
            }
        }
    })

private suspend fun <T> compare(
    provider: AiProviderType,
    timeoutMillis: Long = 15_000,
    run: suspend (AiClient, AiCompatibilityServer, Boolean) -> T,
): List<T> =
    listOf(false, true).map { sdk ->
        withAiCompatibilityClient(provider = provider, sdk = sdk, timeoutMillis = timeoutMillis) { client, server ->
            run(client, server, sdk)
        }
    }

private fun summaryRequest(
    provider: AiProviderType,
    baseUrl: String,
): AiSummaryClientRequest =
    AiSummaryClientRequest(
        providerType = provider,
        baseUrl = baseUrl,
        apiKey = "test-key-${RandomFixture.randomId()}",
        model = "test-model-${RandomFixture.randomId()}",
        prompt = RandomFixture.randomSentenceWithMax(80),
    )

private fun validationRequest(server: AiCompatibilityServer): AiApiKeyValidationRequest =
    AiApiKeyValidationRequest(baseUrl = server.baseUrl, apiKey = "test-key-${RandomFixture.randomId()}")

private suspend fun executeProbe(
    client: AiClient,
    method: String,
    request: AiSummaryClientRequest,
) {
    if (method == "GET") {
        client.validateApiKey(AiApiKeyValidationRequest(baseUrl = request.baseUrl, apiKey = request.apiKey))
    } else {
        client.summarize(request)
    }
}

private fun probeResponse(
    provider: AiProviderType,
    method: String,
): String = if (method == "GET") """{"data":[],"models":[]}""" else summaryEnvelope(provider)

private fun summaryEnvelope(
    provider: AiProviderType,
    text: String = AiCompatibilityFixture.summary().rawResponse,
): String = AiCompatibilityFixture.envelope(provider = provider, groups = listOf(listOf(text)))

private fun label(sdk: Boolean): String = if (sdk) "sdk" else "ktor"

private fun trialKey(
    request: AiCompatibilityServer.Request,
    provider: AiProviderType,
    sdk: Boolean,
): String? =
    when {
        provider == AiProviderType.OPENAI -> {
            request.authorization?.removePrefix("Bearer ")
        }

        sdk -> {
            request.googleApiKey
        }

        else -> {
            request.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("key=") }
                ?.removePrefix("key=")
        }
    }

private fun assertTrialKey(
    request: AiCompatibilityServer.Request,
    provider: AiProviderType,
    sdk: Boolean,
    expected: String,
) {
    trialKey(request, provider, sdk) shouldBe expected
    when {
        provider == AiProviderType.OPENAI -> {
            request.authorization shouldBe "Bearer $expected"
            request.googleApiKey shouldBe null
            request.query shouldBe null
        }

        sdk -> {
            request.authorization shouldBe null
            request.query.orEmpty().contains("key=") shouldBe false
        }

        else -> {
            request.googleApiKey shouldBe null
            request.query shouldBe "key=$expected"
        }
    }
}

private fun expectedOpenAiBody(request: AiSummaryClientRequest): JsonObject {
    val instruction = Json.encodeToString(AiSummarySpec.systemInstruction)
    val prompt = Json.encodeToString(request.prompt)
    val model = Json.encodeToString(request.model)
    val schema = AiSummarySpec.openAiJsonSchema
    return Json
        .parseToJsonElement(
            """
            {
              "model":$model,"instructions":$instruction,
              "input":[{"role":"user","content":[{"type":"input_text","text":$prompt}]}],
              "tools":[{"type":"web_search"}],
              "text":{"format":{"type":"json_schema","name":"${AiSummarySpec.RESPONSE_SCHEMA_NAME}","strict":true,"schema":$schema}}
            }
            """.trimIndent(),
        ).jsonObject
}

private fun assertGeminiBody(
    body: JsonObject,
    request: AiSummaryClientRequest,
    sdk: Boolean,
) {
    val instruction = Json.encodeToString(AiSummarySpec.systemInstruction)
    val prompt = Json.encodeToString(request.prompt)
    val schema = AiSummarySpec.geminiResponseSchema
    val systemField = if (sdk) "systemInstruction" else "system_instruction"
    val toolField = if (sdk) "urlContext" else "url_context"
    body shouldBe
        Json
            .parseToJsonElement(
                """
                {
                  "$systemField":{"parts":[{"text":$instruction}]},
                  "contents":[{"parts":[{"text":$prompt}]}],
                  "tools":[{"$toolField":{}}],
                  "generationConfig":{"responseMimeType":"application/json","responseJsonSchema":$schema}
                }
                """.trimIndent(),
            ).jsonObject
}

private fun model(
    provider: AiProviderType,
    request: AiCompatibilityServer.Request,
): String? =
    if (request.method == "GET") {
        null
    } else if (provider == AiProviderType.OPENAI) {
        request.body!!
            .jsonObject
            .getValue("model")
            .jsonPrimitive.content
    } else {
        request.path.substringAfterLast("/").substringBefore(":")
    }
