package com.qlink.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.AutomaticFunctionCallingConfig
import com.google.genai.types.ClientOptions
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class GeminiSdkExperiment(
    private val timeoutMillis: Long,
) : AiClient,
    AutoCloseable {
    override val providerType = AiProviderType.GEMINI

    private val transport =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    private val clients = ConcurrentHashMap<AiApiKeyValidationRequest, Client>()
    private val config =
        GenerateContentConfig
            .builder()
            .systemInstruction(Content.builder().parts(Part.builder().text(AiSummarySpec.systemInstruction)))
            .tools(listOf(Tool.builder().urlContext(UrlContext.builder()).build()))
            .responseMimeType("application/json")
            .responseJsonSchema(ObjectMapper().readTree(AiSummarySpec.geminiResponseSchema.toString()))
            .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true))
            .build()

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val response =
            client(AiApiKeyValidationRequest(baseUrl = request.baseUrl, apiKey = request.apiKey))
                .async.models
                .generateContent(
                    request.model,
                    Content.builder().parts(Part.builder().text(request.prompt)).build(),
                    config,
                ).await()
        val text =
            response
                .candidates()
                .orElse(emptyList())
                .firstOrNull()
                ?.content()
                ?.orElse(null)
                ?.parts()
                ?.orElse(emptyList())
                ?.firstNotNullOfOrNull {
                    it
                        .text()
                        .orElse(null)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        val usage = response.usageMetadata().orElse(null)
        return parseSummaryResponse(
            rawResponse = text,
            usedTokens = if (usage == null) text.length else usage.totalTokenCount().orElse(0),
        )
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        try {
            withContext(Dispatchers.IO) {
                client(request).models.list(null)
            }
        } catch (exception: ApiException) {
            throw AiApiKeyValidationException(exception.code())
        }
    }

    private fun client(request: AiApiKeyValidationRequest): Client =
        clients.computeIfAbsent(request) {
            Client
                .builder()
                .apiKey(it.apiKey)
                .vertexAI(false)
                .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
                .httpOptions(
                    HttpOptions
                        .builder()
                        .baseUrl(it.baseUrl)
                        .apiVersion("")
                        .timeout(timeoutMillis.toInt())
                        .retryOptions(HttpRetryOptions.builder().attempts(1))
                        .build(),
                ).build()
        }

    override fun close() {
        clients.values.forEach(Client::close)
    }
}
