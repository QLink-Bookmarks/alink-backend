package com.qlink.support

import com.qlink.ai.client.AiApiKeyValidationException
import com.qlink.ai.client.AiApiKeyValidationRequest
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummaryClientRequest
import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.client.AiSummaryTodo
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.RandomFixture
import org.koin.dsl.module

fun aiTestModule() =
    module {
        single {
            ApiKeyCipher(
                keyBase64 = TEST_AI_API_KEY_ENCRYPTION_KEY_BASE64,
                invalidKeyErrorCode = ErrorCode.CONFIG_AI_API_KEY_ENCRYPTION_KEY_INVALID,
            )
        }

        single {
            FakeAiClient()
        }

        single<AiClientRouter> {
            AiClientRouter(
                clients = listOf(get<FakeAiClient>()),
            )
        }
    }

class FakeAiClient : AiClient {
    override val providerType: AiProviderType = AiProviderType.OPENAI
    var linkId: Long? = null
    var folderId: Long? = null
    var tags: List<String> = listOf("AI 태그")
    var usedTokens: Int = 11
    var failuresByModel: Map<String, Throwable> = emptyMap()
    val requestedModels: MutableList<String> = mutableListOf()
    val requestedApiKeys: MutableList<String> = mutableListOf()
    var validationStatusCode: Int? = null
    var beforeSummary: (suspend (AiSummaryClientRequest) -> Unit)? = null

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        requestedModels.add(request.model)
        requestedApiKeys.add(request.apiKey)
        beforeSummary?.invoke(request)
        failuresByModel[request.model]?.let { throw it }

        return AiSummaryClientResponse(
            linkId = linkId,
            folderId = folderId,
            rawResponse = """{"id":1,"title":"AI 제목","summary":"AI 요약","todos":[{"title":"AI 할 일","reminderAt":null}]}""",
            title = "AI 제목",
            summary = "AI 요약",
            tags = tags,
            todos = listOf(AiSummaryTodo(title = "AI 할 일", reminderAt = null)),
            usedTokens = usedTokens,
        )
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        requestedApiKeys.add(request.apiKey)
        validationStatusCode?.let { throw AiApiKeyValidationException(it) }
    }

    fun reset() {
        linkId = null
        folderId = null
        tags = listOf("AI 태그")
        usedTokens = 11
        failuresByModel = emptyMap()
        requestedModels.clear()
        requestedApiKeys.clear()
        validationStatusCode = null
        beforeSummary = null
    }
}

suspend fun insertAiContext(
    userId: Long,
    aiProviderRepository: AiProviderRepository,
    availableModelRepository: AvailableModelRepository,
    userProviderRepository: UserProviderRepository,
    role: Role = Role.NORMAL,
    type: AiProviderType = AiProviderType.OPENAI,
): Pair<UserProvider, AvailableModel> {
    val provider =
        aiProviderRepository.insert(
            AiProvider(
                type = type,
                baseUrl = "https://example.com",
                activeAt = RandomFixture.randomPastInstant(),
            ),
        )
    val model =
        availableModelRepository.insert(
            AvailableModel(
                providerId = provider.id!!,
                model = "test-model-${provider.id}",
                priority = 1,
                rpdLimit = 20,
                tpdLimit = 2_000_000,
                activeAt = RandomFixture.randomPastInstant(),
            ),
        )
    val userProvider =
        userProviderRepository.insert(
            UserProvider(
                userId = userId,
                providerId = provider.id,
                userRole = role,
                apiKey =
                    ApiKeyCipher(
                        keyBase64 = TEST_AI_API_KEY_ENCRYPTION_KEY_BASE64,
                        invalidKeyErrorCode = ErrorCode.CONFIG_AI_API_KEY_ENCRYPTION_KEY_INVALID,
                    ).encrypt("api-key"),
            ),
        )

    return userProvider to model
}

const val TEST_AI_API_KEY_ENCRYPTION_KEY_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
