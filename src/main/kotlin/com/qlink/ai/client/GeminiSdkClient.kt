package com.qlink.ai.client

import com.google.genai.Client
import com.google.genai.errors.GenAiIOException
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.ListModelsConfig
import com.google.genai.types.Part
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.AiSdkConfig.Companion.GEMINI_API_KEY_HEADER
import com.qlink.config.AiSdkHttpTransport
import java.io.IOException

internal class GeminiSdkClient(
    private val client: Client,
    private val transport: AiSdkHttpTransport,
    private val config: GenerateContentConfig,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.GEMINI

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        request.validatePrompt()
        val body =
            execute {
                client.models
                    .generateContent(
                        request.model,
                        Content.builder().parts(Part.builder().text(request.prompt)).build(),
                        config.toBuilder().httpOptions(keyOptions(request.apiKey)).build(),
                    ).sdkHttpResponse()
                    .flatMap { it.body() }
                    .orElse("")
            }

        return parseGeminiSummary(body)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        execute {
            client.models.list(ListModelsConfig.builder().httpOptions(keyOptions(request.apiKey)).build())
        }
    }

    private fun keyOptions(apiKey: String): HttpOptions = HttpOptions.builder().headers(mapOf(GEMINI_API_KEY_HEADER to apiKey)).build()

    private suspend fun <T> execute(block: () -> T): T =
        try {
            transport.execute(block)
        } catch (_: GenAiIOException) {
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        } catch (_: IOException) {
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        }
}
