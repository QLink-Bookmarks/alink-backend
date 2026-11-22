package com.qlink.config

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.ktor.server.config.ApplicationConfig

internal class OpenAiConfig(
    config: ApplicationConfig,
) {
    val sdkEndpoint by lazy {
        (config.optionalString("ai.openai.sdkEndpoint") ?: System.getenv("AI_OPENAI_SDK_ENDPOINT"))
            ?.takeIf(String::isNotBlank) ?: "https://api.openai.com/v1"
    }

    private val mapper by lazy { ObjectMapper() }

    val webSearch by lazy { WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build() }

    val summary: ResponseTextConfig by lazy {
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

    fun client(transport: AiSdkHttpTransport): OpenAIClient =
        OpenAIOkHttpClient
            .builder()
            .baseUrl(sdkEndpoint)
            .httpRequestAuthenticator(
                object : HttpRequestAuthenticator {
                    override fun authenticate(request: HttpRequest): HttpRequest = request
                },
            ).okHttpClient(transport.client)
            .maxRetries(0)
            .logLevel(LogLevel.OFF)
            .build()
}
