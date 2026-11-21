package com.qlink.ai.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiResponsesResponse(
    val output: List<OpenAiOutput> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiOutput(
    val content: List<AiResponseText> = emptyList(),
)

@Serializable
internal data class AiResponseText(
    val text: String? = null,
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)

@Serializable
internal data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
internal data class GeminiContent(
    val parts: List<AiResponseText> = emptyList(),
)

@Serializable
internal data class GeminiUsageMetadata(
    val totalTokenCount: Int = 0,
)
