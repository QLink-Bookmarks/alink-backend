package com.qlink.config

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
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.client.geminiHttpError
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.ktor.server.config.ApplicationConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.blackholeSink

internal class GeminiConfig(
    config: ApplicationConfig,
) {
    val sdkEndpoint by lazy {
        (config.optionalString("ai.gemini.sdkEndpoint") ?: System.getenv("AI_GEMINI_SDK_ENDPOINT"))
            ?.takeIf(String::isNotBlank) ?: "https://generativelanguage.googleapis.com/v1beta"
    }

    private val mapper by lazy { ObjectMapper() }

    val summary: GenerateContentConfig by lazy {
        GenerateContentConfig
            .builder()
            .systemInstruction(Content.builder().parts(Part.builder().text(AiSummarySpec.systemInstruction)))
            .tools(listOf(Tool.builder().urlContext(UrlContext.builder()).build()))
            .responseMimeType("application/json")
            .responseJsonSchema(mapper.readTree(AiSummarySpec.geminiResponseSchema.toString()))
            .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true))
            .shouldReturnHttpResponse(true)
            .build()
    }

    fun client(transport: AiSdkHttpTransport): Client =
        Client
            .builder()
            .apiKey("")
            .vertexAI(false)
            .clientOptions(ClientOptions.builder().customHttpClient(authenticatedTransport(transport)).build())
            .httpOptions(
                HttpOptions
                    .builder()
                    .baseUrl(sdkEndpoint)
                    .apiVersion("")
                    .retryOptions(HttpRetryOptions.builder().attempts(1))
                    .build(),
            ).build()

    private fun authenticatedTransport(transport: AiSdkHttpTransport) =
        transport.client
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val apiKey =
                    request
                        .tag(HttpOptions::class.java)
                        ?.headers()
                        ?.orElse(emptyMap())
                        ?.get(GEMINI_API_KEY_HEADER)
                        ?: throw BusinessException(ErrorCode.AI_API_KEY_INVALID)
                val response = chain.proceed(request.newBuilder().header(GEMINI_API_KEY_HEADER, apiKey).build())
                when {
                    !response.isSuccessful -> {
                        response.use {
                            val body =
                                if (it.code in 400..499) {
                                    it.peekBody(MAX_ERROR_BODY_BYTES).string()
                                } else {
                                    ""
                                }
                            throw BusinessException(geminiHttpError(status = it.code, body = body))
                        }
                    }

                    request.method == "GET" -> {
                        response.use { it.body?.source()?.readAll(blackholeSink()) }
                        response.newBuilder().body("""{"models":[]}""".toResponseBody("application/json".toMediaType())).build()
                    }

                    else -> {
                        response
                    }
                }
            }.build()

    companion object {
        const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024
    }
}
