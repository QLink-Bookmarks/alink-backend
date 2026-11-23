package com.qlink.ai.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.repository.table.AiProviders
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.transaction.TransactionRunner
import com.qlink.config.SecurityConfig
import com.qlink.di.pluginModule
import com.qlink.di.repositoryModule
import com.qlink.di.serviceModule
import com.qlink.plugin.configureDocs
import com.qlink.plugin.configureRequestValidation
import com.qlink.plugin.configureResources
import com.qlink.plugin.configureRouting
import com.qlink.plugin.configureSecurity
import com.qlink.plugin.configureSerialization
import com.qlink.plugin.configureStatusPages
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.koin.dsl.module
import org.koin.ktor.plugin.KoinIsolated
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class AiAdminHttpContractTest :
    BaseServiceTest({
        val tx = koinGet<TransactionRunner>()
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val usages = koinGet<DailyUsageRepository>()

        Given("Ktor, PostgreSQL과 테스트 제공자") {
            lateinit var admin: User
            lateinit var nonAdmin: User
            lateinit var nextAdmin: User
            lateinit var provider: AiProvider
            lateinit var model: AvailableModel

            beforeTest {
                if (it.type != TestType.Test) {
                    return@beforeTest
                }
                CoroutineScope(Dispatchers.IO)
                    .async {
                        tx.required {
                            nonAdmin = users.insert(UserFixture.createRandomValidUser())
                            admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                            nextAdmin = users.insert(UserFixture.createRandomValidSuperAdmin())
                            provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI))
                            model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!))
                        }
                    }.await()
            }

            afterTest { (test, _) ->
                if (test.type != TestType.Test) {
                    return@afterTest
                }
                CoroutineScope(Dispatchers.IO)
                    .async {
                        tx.required {
                            AiProviders.deleteWhere { AiProviders.id eq provider.id!! }
                            users.deleteById(admin.id!!)
                            users.deleteById(nonAdmin.id!!)
                            users.deleteById(nextAdmin.id!!)
                        }
                    }.await()
            }

            When("공용 키와 특정 UTC 날짜의 사용량을 HTTP로 요청하면") {
                Then("관리자 권한을 확인하고 계약의 응답 구조를 반환한다") {
                    val config = ServiceTestEnvironment.applicationConfig()
                    val jwt = Algorithm.HMAC256(SecurityConfig.from(config).jwtSecret)

                    fun token(user: User) =
                        JWT
                            .create()
                            .withSubject(user.id.toString())
                            .withClaim("role", Role.SUPER_ADMIN.name)
                            .sign(jwt)

                    val providerId = provider.id!!
                    val keyPath = "/api/ai/providers/$providerId/shared-key"
                    val usagePath = "/api/ai/daily-usage"
                    val day = LocalDate.now(ZoneOffset.UTC)
                    val apiKey = "dummy-http-key-${RandomFixture.randomId()}"
                    val vendor = FakeAiClient()
                    val adminToken = token(admin)

                    testApplication {
                        environment { this.config = MapApplicationConfig() }
                        application { configureAiHttpContract(config = config, vendor = vendor) }

                        client.get(keyPath).status shouldBe HttpStatusCode.Unauthorized
                        client.get(keyPath) { bearerAuth(token(nonAdmin)) }.status shouldBe HttpStatusCode.Forbidden
                        client.get(keyPath) { bearerAuth(adminToken) }.successData() shouldBe
                            Json.parseToJsonElement("""{"lastFour":null}""").jsonObject

                        val malformed =
                            client.put(keyPath) {
                                bearerAuth(adminToken)
                                contentType(ContentType.Application.Json)
                                setBody("""{"apiKey":"$apiKey" """)
                            }
                        malformed.status shouldBe HttpStatusCode.BadRequest
                        malformed.bodyAsText().contains(apiKey) shouldBe false

                        val masked = buildJsonObject { put("lastFour", apiKey.takeLast(4)) }
                        client
                            .put(keyPath) {
                                bearerAuth(adminToken)
                                contentType(ContentType.Application.Json)
                                setBody(buildJsonObject { put("apiKey", apiKey) }.toString())
                            }.successData() shouldBe masked
                        client.get(keyPath) { bearerAuth(adminToken) }.successData() shouldBe masked
                        (vendor.requestedApiKeys.single() == apiKey) shouldBe true

                        client.get(usagePath) { bearerAuth(adminToken) }.status shouldBe HttpStatusCode.BadRequest
                        client.get("$usagePath?date=$day") { bearerAuth(adminToken) }.successData() shouldBe
                            Json.parseToJsonElement("""{"date":"$day","items":[]}""").jsonObject

                        val recorded =
                            CoroutineScope(Dispatchers.IO)
                                .async {
                                    tx.required {
                                        usages.insert(
                                            DailyUsage(
                                                userProviderId =
                                                    keys
                                                        .findByUserIdAndProviderId(
                                                            userId = admin.id!!,
                                                            providerId = providerId,
                                                        )!!
                                                        .id!!,
                                                modelId = model.id!!,
                                                usageDate = day,
                                                requests = RandomFixture.randomInt(1, 100),
                                                tokens = RandomFixture.randomInt(1, 1_000),
                                            ),
                                        )
                                    }
                                }.await()
                        val expected =
                            """
                            {"date":"$day","items":[{
                                "providerId":$providerId,"providerType":"${provider.type}","modelId":${model.id},
                                "model":"${model.model}","requests":${recorded.requests},"tokens":${recorded.tokens}
                            }]}
                            """.trimIndent()
                        client.get("$usagePath?date=$day") { bearerAuth(adminToken) }.successData() shouldBe
                            Json.parseToJsonElement(expected).jsonObject
                    }
                }
            }

            When("두 관리자의 최초 등록 요청이 같은 제공자에서 겹치면") {
                Then("공용 행 한 건을 만들고 첫 관리자와 행 ID를 유지한다") {
                    val config = ServiceTestEnvironment.applicationConfig()
                    val jwt = Algorithm.HMAC256(SecurityConfig.from(config).jwtSecret)

                    fun token(user: User) =
                        JWT
                            .create()
                            .withSubject(user.id.toString())
                            .withClaim("role", user.role.name)
                            .sign(jwt)

                    val providerId = provider.id!!
                    val keyPath = "/api/ai/providers/$providerId/shared-key"
                    val validatingRequests = AtomicInteger()
                    val firstValidating = CompletableDeferred<Unit>()
                    val finishFirstValidation = CompletableDeferred<Unit>()
                    val secondValidating = CompletableDeferred<Unit>()
                    val vendor =
                        object : AiClient by FakeAiClient() {
                            override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
                                if (validatingRequests.incrementAndGet() == 1) {
                                    firstValidating.complete(Unit)
                                    withTimeout(10_000) { finishFirstValidation.await() }
                                } else {
                                    secondValidating.complete(Unit)
                                }
                            }
                        }

                    testApplication {
                        environment { this.config = MapApplicationConfig() }
                        application { configureAiHttpContract(config = config, vendor = vendor) }

                        suspend fun putKey(
                            user: User,
                            key: String,
                        ) = client
                            .put(keyPath) {
                                bearerAuth(token(user))
                                contentType(ContentType.Application.Json)
                                setBody(buildJsonObject { put("apiKey", key) }.toString())
                            }.successData()

                        suspend fun sharedIds() =
                            CoroutineScope(Dispatchers.IO)
                                .async {
                                    tx.readOnly {
                                        keys
                                            .findAllByRole(Role.SUPER_ADMIN)
                                            .filter { it.providerId == providerId }
                                            .map { it.userId to it.id }
                                    }
                                }.await()

                        val submitted = List(2) { "dummy-concurrent-${RandomFixture.randomId()}" }
                        val responses =
                            coroutineScope {
                                val first = async { putKey(user = admin, key = submitted[0]) }
                                withTimeout(10_000) { firstValidating.await() }
                                val second =
                                    async(start = CoroutineStart.UNDISPATCHED) {
                                        putKey(user = nextAdmin, key = submitted[1])
                                    }
                                try {
                                    withTimeoutOrNull(250) { secondValidating.await() } shouldBe null
                                } finally {
                                    finishFirstValidation.complete(Unit)
                                }
                                listOf(first, second).awaitAll()
                            }
                        responses shouldBe submitted.map { key -> buildJsonObject { put("lastFour", key.takeLast(4)) } }

                        val first = sharedIds().single()
                        first.first shouldBe admin.id
                        putKey(user = nextAdmin, key = "dummy-rotation-${RandomFixture.randomId()}")
                        sharedIds() shouldBe listOf(first)
                    }
                }
            }
        }
    })

private fun Application.configureAiHttpContract(
    config: ApplicationConfig,
    vendor: AiClient,
) {
    val tx = koinGet<TransactionRunner>()
    val cipher = koinGet<ApiKeyCipher>()
    install(KoinIsolated) {
        modules(
            pluginModule(config),
            repositoryModule(),
            serviceModule(),
            module {
                single { tx }
                single { cipher }
                single { AiClientRouter(clients = listOf(vendor)) }
            },
        )
    }
    configureSerialization()
    configureSecurity()
    configureResources()
    configureDocs()
    configureStatusPages()
    configureRequestValidation()
    configureRouting()
}

private suspend fun HttpResponse.successData(): JsonObject {
    status shouldBe HttpStatusCode.OK
    val envelope = Json.parseToJsonElement(bodyAsText()).jsonObject
    envelope.getValue("success") shouldBe JsonPrimitive(true)
    return envelope.getValue("data").jsonObject
}
