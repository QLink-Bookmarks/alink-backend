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
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.core.LogLevel
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpRequestAuthenticator
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.WebSearchTool
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.client.geminiHttpError
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.ktor.server.config.ApplicationConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.blackholeSink

internal class AiSdkConfig(
    private val config: ApplicationConfig,
) {
    val openAiEnabled = useSdk("ai.openai.useSdk", "AI_OPENAI_USE_SDK", ErrorCode.CONFIG_OPENAI_USE_SDK_INVALID)
    val geminiEnabled = useSdk("ai.gemini.useSdk", "AI_GEMINI_USE_SDK", ErrorCode.CONFIG_GEMINI_USE_SDK_INVALID)

    private val mapper by lazy { ObjectMapper() }

    val openAiWebSearch by lazy { WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build() }

    val openAiSummary: ResponseTextConfig by lazy {
        ResponseTextConfig
            .builder()
            .format(
                ResponseFormatTextJsonSchemaConfig
                    .builder()
                    .name(AiSummarySpec.RESPONSE_SCHEMA_NAME)
                    .strict(true)
                    .schema(
                        ResponseFormatTextJsonSchemaConfig.Schema
                            .builder()
                            .apply {
                                AiSummarySpec.openAiJsonSchema.forEach { (key, value) ->
                                    putAdditionalProperty(key, JsonValue.fromJsonNode(mapper.readTree(value.toString())))
                                }
                            }.build(),
                    ).build(),
            ).build()
    }

    val geminiSummary: GenerateContentConfig by lazy {
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

    fun openAiClient(transport: AiSdkHttpTransport): OpenAIClient =
        OpenAIOkHttpClient
            .builder()
            .baseUrl(setting("ai.openai.sdkEndpoint", "AI_OPENAI_SDK_ENDPOINT") ?: "https://api.openai.com/v1")
            .httpRequestAuthenticator(
                object : HttpRequestAuthenticator {
                    override fun authenticate(request: HttpRequest): HttpRequest = request
                },
            ).okHttpClient(transport.client)
            .maxRetries(0)
            .logLevel(LogLevel.OFF)
            .build()

    fun geminiClient(transport: AiSdkHttpTransport): Client =
        Client
            .builder()
            .apiKey("")
            .vertexAI(false)
            .clientOptions(ClientOptions.builder().customHttpClient(geminiTransport(transport)).build())
            .httpOptions(
                HttpOptions
                    .builder()
                    .baseUrl(
                        setting("ai.gemini.sdkEndpoint", "AI_GEMINI_SDK_ENDPOINT") ?: "https://generativelanguage.googleapis.com/v1beta",
                    ).apiVersion("")
                    .retryOptions(HttpRetryOptions.builder().attempts(1))
                    .build(),
            ).build()

    private fun geminiTransport(transport: AiSdkHttpTransport) =
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

    private fun useSdk(
        path: String,
        environmentVariable: String,
        errorCode: ErrorCode,
    ): Boolean {
        val value =
            try {
                config.optionalString(path) ?: System.getenv(environmentVariable)
            } catch (_: RuntimeException) {
                throw BusinessException(errorCode)
            }
        return value?.let { it.toBooleanStrictOrNull() ?: throw BusinessException(errorCode) } ?: true
    }

    private fun setting(
        path: String,
        environmentVariable: String,
    ): String? = (config.optionalString(path) ?: System.getenv(environmentVariable))?.takeIf(String::isNotBlank)

    companion object {
        const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024
    }
}
