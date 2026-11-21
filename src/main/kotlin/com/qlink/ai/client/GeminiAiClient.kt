package com.qlink.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.genai.Client
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
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class GeminiAiClient :
    AiClient,
    AutoCloseable {
    override val providerType: AiProviderType = AiProviderType.GEMINI

    private val transport = AiSdkHttpTransport()
    private val clientOptions = ClientOptions.builder().customHttpClient(transport.client).build()
    private val validationOptions =
        ClientOptions
            .builder()
            .customHttpClient(
                transport.client
                    .newBuilder()
                    .addInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (!response.isSuccessful) {
                            response.close()
                            throw AiApiKeyValidationException(response.code)
                        }
                        response.close()
                        response.newBuilder().body("{}".toResponseBody("application/json".toMediaType())).build()
                    }.build(),
            ).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val config =
        GenerateContentConfig
            .builder()
            .systemInstruction(Content.builder().parts(Part.builder().text(AiSummarySpec.systemInstruction)))
            .tools(listOf(Tool.builder().urlContext(UrlContext.builder()).build()))
            .responseMimeType("application/json")
            .responseJsonSchema(ObjectMapper().readTree(AiSummarySpec.geminiResponseSchema.toString()))
            .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true))
            .shouldReturnHttpResponse(true)
            .build()

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val response =
            transport.execute {
                val raw =
                    client(baseUrl = request.baseUrl, apiKey = request.apiKey, options = clientOptions)
                        .models
                        .generateContent(
                            request.model,
                            Content.builder().parts(Part.builder().text(request.prompt)).build(),
                            config,
                        ).sdkHttpResponse()
                        .flatMap { it.body() }
                        .orElse("")
                json.decodeFromString<GeminiGenerateContentResponse>(raw)
            }
        val text =
            response.candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.firstNotNullOfOrNull { it.text?.trim()?.takeIf(String::isNotBlank) }
                ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)

        return parseSummaryResponse(rawResponse = text, usedTokens = response.usageMetadata?.totalTokenCount ?: text.length)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        transport.execute {
            client(baseUrl = request.baseUrl, apiKey = request.apiKey, options = validationOptions).models.list(null)
        }
    }

    private fun client(
        baseUrl: String,
        apiKey: String,
        options: ClientOptions,
    ): Client =
        Client
            .builder()
            .apiKey(apiKey)
            .vertexAI(false)
            .clientOptions(options)
            .httpOptions(
                HttpOptions
                    .builder()
                    .baseUrl(baseUrl)
                    .apiVersion("")
                    .retryOptions(HttpRetryOptions.builder().attempts(1))
                    .build(),
            ).build()

    override fun close() = transport.close()
}
