package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.ai.dto.GeminiContent
import com.qlink.ai.dto.GeminiGenerateContentRequest
import com.qlink.ai.dto.GeminiGenerationConfig
import com.qlink.ai.dto.GeminiPart
import com.qlink.ai.dto.GeminiTool
import com.qlink.plugin.omitExternalHttpResponseDetails
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GeminiCurlClient(
    private val httpClient: HttpClient,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.GEMINI

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        request.validatePrompt()
        val body =
            executeVendorHttp {
                httpClient
                    .post("${request.baseUrl}/models/${request.model}:generateContent") {
                        attributes.put(omitExternalHttpResponseDetails, Unit)
                        parameter("key", request.apiKey)
                        contentType(ContentType.Application.Json)
                        setBody(geminiSummaryRequest(request.prompt))
                    }.let {
                        it.verifyVendorStatus(::geminiHttpError)
                        it.bodyAsText()
                    }
            }
        return parseGeminiSummary(body)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        executeVendorHttp {
            httpClient
                .get("${request.baseUrl}/models") {
                    attributes.put(omitExternalHttpResponseDetails, Unit)
                    parameter("key", request.apiKey)
                }.verifyVendorStatus(::geminiHttpError)
        }
    }
}

private fun geminiSummaryRequest(prompt: String): GeminiGenerateContentRequest =
    GeminiGenerateContentRequest(
        systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = AiSummarySpec.systemInstruction))),
        contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
        tools = listOf(GeminiTool(urlContext = emptyMap())),
        generationConfig = GeminiGenerationConfig(),
    )
